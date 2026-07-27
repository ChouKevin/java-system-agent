package com.java.system.agent;

import com.java.system.agent.inbox.application.SessionInboxApplicationService;
import com.java.system.agent.inbox.application.SessionInboxProcessor;
import com.java.system.agent.inbox.domain.InboxMessage;
import com.java.system.agent.inbox.domain.InboxMessageStatus;
import com.java.system.agent.inbox.domain.InboxProcessingOutcome;
import com.java.system.agent.inbox.domain.SessionSourceRef;
import com.java.system.agent.inbox.domain.SourceMessageId;
import com.java.system.agent.inbox.port.in.EnqueueSessionMessageCommand;
import com.java.system.agent.model.action.AgentActionPromptRenderer;
import com.java.system.agent.model.verification.AnswerVerificationPromptRenderer;
import com.java.system.agent.persistence.jdbc.PostgresAgentTransitionAdapter;
import com.java.system.agent.persistence.jdbc.PostgresSessionInboxAdapter;
import com.java.system.agent.persistence.jdbc.PostgresSessionAdapter;
import com.java.system.agent.runtime.domain.answer.AnswerVerificationBasis;
import com.java.system.agent.runtime.domain.conversation.ConversationTurnType;
import com.java.system.agent.runtime.domain.run.AgentRunState;
import com.java.system.agent.runtime.domain.run.AgentRunStatus;
import com.java.system.agent.runtime.domain.run.PendingTerminalResponse;
import com.java.system.agent.runtime.domain.run.RunResponseKind;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.port.in.AnswerQuestionCommand;
import com.java.system.agent.runtime.port.in.AnswerQuestionResult;
import com.java.system.agent.runtime.port.in.AnswerQuestionUseCase;
import com.java.system.agent.support.CallTimeline;
import com.java.system.agent.support.ControllableChatModel;
import com.java.system.agent.support.M2IntegrationTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;

/**
 * 驗證 M2 production composition 從 durable inbox 到唯一已驗證答案的完整 PostgreSQL 流程
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.google.genai.api-key=",
        "agent.answer-verification.mode=llm",
        "agent.codebase.base-url=http://semantic.test",
        "agent.codebase.api-token=m2-token",
        "agent.codebase.connect-timeout=2s",
        "agent.codebase.read-timeout=15s"
})
@ActiveProfiles({"agent-runtime", "m2-flow-it"})
@Import(M2IntegrationTestConfiguration.class)
class M2ProductionFlowIT {

    private static final Instant NOW = Instant.parse("2030-07-27T10:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5-alpine");

    @Autowired
    private org.flywaydb.core.Flyway flyway;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private SessionInboxApplicationService enqueueService;

    @Autowired
    private PostgresSessionInboxAdapter inbox;

    @Autowired
    private SessionInboxProcessor processor;

    @Autowired
    private AnswerQuestionUseCase answerQuestionUseCase;

    @Autowired
    private PostgresAgentTransitionAdapter transitions;

    @Autowired
    private PostgresSessionAdapter sessions;

    @Autowired
    private ControllableChatModel chatModel;

    @Autowired
    private CallTimeline callTimeline;

    @Autowired
    private MockRestServiceServer server;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private Environment environment;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void resetFlowState() {
        server.reset();
        callTimeline.clear();
    }

    @Test
    void processesOneClaimedMessageThroughTheProductionDurableAnswerFlow() {
        assertThat(dataSource).isInstanceOf(DriverManagerDataSource.class);
        assertThat(transactionTemplate).isNotNull();
        assertThat(flyway.info().current()).isNotNull();

        InboxMessage enqueued = enqueueService.enqueue(new EnqueueSessionMessageCommand(
                new SessionSourceRef("slack", "channel-1:thread-1"),
                new SourceMessageId("message-1"),
                "Which entry point remains unresolved?"));
        String attemptId = enqueued.runId().value() + ":A1";

        server.expect(requestTo("http://semantic.test/v1/repositories"))
                .andExpect(method(GET))
                .andExpect(header("X-Api-Token", "m2-token"))
                .andExpect(request -> callTimeline.record(CallTimeline.HTTP_REPOSITORY_CATALOG))
                .andRespond(withSuccess(repositoryCatalogJson(), APPLICATION_JSON));
        server.expect(requestTo("http://semantic.test/v1/repositories/demo"))
                .andExpect(method(GET))
                .andExpect(header("X-Api-Token", "m2-token"))
                .andExpect(request -> callTimeline.record(CallTimeline.HTTP_REPOSITORY_REVISION))
                .andRespond(withSuccess(repositoryStatusJson(), APPLICATION_JSON));
        server.expect(requestTo("http://semantic.test/v1/repositories/demo/entry-points"))
                .andExpect(method(GET))
                .andExpect(header("X-Api-Token", "m2-token"))
                .andExpect(request -> callTimeline.record(CallTimeline.HTTP_LIST_ENTRY_POINTS))
                .andRespond(withSuccess(entryPointsJson(), APPLICATION_JSON));

        chatModel.enqueue(CallTimeline.LLM_QUERY_ACTION, queryJson(attemptId));
        chatModel.enqueue(CallTimeline.LLM_ANSWER_ACTION, answerJson(attemptId));
        chatModel.enqueue(CallTimeline.LLM_VERIFIER, verdictJson());

        InboxMessage claimed = inbox.claimNext(NOW).orElseThrow();
        InboxProcessingOutcome outcome = processor.process(claimed, NOW);

        assertThat(outcome).isEqualTo(InboxProcessingOutcome.COMPLETED);
        assertThat(inboxStatus(enqueued)).isEqualTo(InboxMessageStatus.COMPLETED.name());
        AgentRunState state = transitions.findByRunId(enqueued.runId()).orElseThrow();
        assertThat(state.status()).isEqualTo(AgentRunStatus.CONCLUDED);
        assertThat(state.finalOutcome()).contains(RunOutcome.COMPLETED);
        assertThat(agentRunStateSchemaVersion(enqueued)).isEqualTo(2);
        assertThat(eventSchemaVersions(enqueued)).isNotEmpty().containsOnly(2);
        assertThat(state.pendingTerminalResponse()).hasValueSatisfying(response -> {
            assertThat(response).isInstanceOf(PendingTerminalResponse.Answer.class);
            PendingTerminalResponse.Answer answer = (PendingTerminalResponse.Answer) response;
            assertThat(answer.acceptance().verificationBasis()).isEqualTo(AnswerVerificationBasis.LLM);
        });
        assertThat(eventTypes(enqueued)).containsSubsequence(
                "ACTION_ACCEPTED", "QUERY_BUDGET_CONSUMED", "ANSWER_PROPOSED", "ANSWER_ACCEPTED", "RUN_CONCLUDED");
        assertThat(eventTypes(enqueued)).filteredOn("ACTION_ACCEPTED"::equals).hasSize(1);
        assertThat(eventTypes(enqueued)).filteredOn("QUERY_BUDGET_CONSUMED"::equals).hasSize(1);
        assertThat(sessionAnswerCount(enqueued)).isEqualTo(1L);
        assertThat(sessions.read(enqueued.sessionId()).turns()).hasSize(1)
                .allMatch(turn -> turn.type() == ConversationTurnType.ANSWER);
        assertThat(chatModel.prompts())
                .extracting(Prompt::getSystemMessage)
                .extracting(SystemMessage::getText)
                .containsExactly(
                        AgentActionPromptRenderer.SYSTEM_INSTRUCTION,
                        AgentActionPromptRenderer.SYSTEM_INSTRUCTION,
                        AnswerVerificationPromptRenderer.SYSTEM_INSTRUCTION);
        AnswerQuestionResult reconciled = answerQuestionUseCase.answer(new AnswerQuestionCommand(
                enqueued.runId(), enqueued.sessionId(), enqueued.exactQuestion(), state.budget()));
        assertThat(reconciled.responseKind()).isEqualTo(RunResponseKind.ANSWER);
        assertThat(reconciled.verificationBasis()).contains(AnswerVerificationBasis.LLM);
        assertThat(chatModel.prompts()).hasSize(3);
        assertThat(callTimeline.calls()).containsExactly(
                CallTimeline.HTTP_REPOSITORY_CATALOG,
                CallTimeline.LLM_QUERY_ACTION,
                CallTimeline.HTTP_REPOSITORY_REVISION,
                CallTimeline.HTTP_LIST_ENTRY_POINTS,
                CallTimeline.LLM_ANSWER_ACTION,
                CallTimeline.LLM_VERIFIER);
        assertThat(environment.getProperty("spring.ai.google.genai.api-key")).isEmpty();
        server.verify();
    }

    private String inboxStatus(InboxMessage message) {
        return jdbcClient.sql("""
                SELECT status
                FROM session_inbox
                WHERE inbox_message_id = :inboxMessageId
                """)
                .param("inboxMessageId", message.inboxMessageId().value())
                .query(String.class)
                .single();
    }

    private List<String> eventTypes(InboxMessage message) {
        return jdbcClient.sql("""
                SELECT event_type
                FROM agent_run_event
                WHERE run_id = :runId
                ORDER BY state_revision
                """)
                .param("runId", message.runId().value())
                .query(String.class)
                .list();
    }

    private int agentRunStateSchemaVersion(InboxMessage message) {
        return jdbcClient.sql("""
                SELECT state_schema_version
                FROM agent_run
                WHERE run_id = :runId
                """)
                .param("runId", message.runId().value())
                .query(Integer.class)
                .single();
    }

    private List<Integer> eventSchemaVersions(InboxMessage message) {
        return jdbcClient.sql("""
                SELECT event_schema_version
                FROM agent_run_event
                WHERE run_id = :runId
                ORDER BY state_revision
                """)
                .param("runId", message.runId().value())
                .query(Integer.class)
                .list();
    }

    private long sessionAnswerCount(InboxMessage message) {
        return jdbcClient.sql("""
                SELECT COUNT(*)
                FROM session_turn
                WHERE session_id = :sessionId
                  AND run_id = :runId
                  AND turn_type = 'ANSWER'
                """)
                .param("sessionId", message.sessionId().value())
                .param("runId", message.runId().value())
                .query(Long.class)
                .single();
    }

    private static String queryJson(String attemptId) {
        return """
                {"type":"QUERY","query":{"capabilityHandle":"%s:C2","candidateHandles":["%s:R1"],"questionToResolve":"Find the unresolved entry point","arguments":{},"rationale":"inspect the repository entry points"},"answer":null,"clarify":null}
                """.formatted(attemptId, attemptId);
    }

    private static String answerJson(String attemptId) {
        return """
                {"type":"ANSWER","query":null,"answer":{"statements":[{"statementId":"limitation-1","type":"LIMITATION","text":"OrderController.list remains unresolved because the semantic service reported TARGET_NOT_FOUND","claimId":null,"citationHandles":[],"observationIds":["%s:O1"]}]},"clarify":null}
                """.formatted(attemptId);
    }

    private static String verdictJson() {
        return """
                {"disposition":"ACCEPTED_COMPLETE","statementVerdicts":[],"unaddressedParts":[],"blockingUncertainties":[],"rejectionReasons":[]}
                """;
    }

    private static String repositoryCatalogJson() {
        return """
                [{"repoId":"demo","mode":"LOCAL_FIXTURE","displayName":"Demo repository","currentBranch":"main","currentRevision":"FIXTURE","cloned":true}]
                """;
    }

    private static String repositoryStatusJson() {
        return """
                {"repoId":"demo","mode":"LOCAL_FIXTURE","displayName":"Demo repository","currentBranch":"main","currentRevision":"FIXTURE","cloned":true}
                """;
    }

    private static String entryPointsJson() {
        return """
                {"repoId":"demo","analyzedRevision":"FIXTURE","entryPoints":[{"className":"OrderController","packageName":"example.web","packagePath":"example/web","description":"Order entry points","basePaths":["/orders"],"methods":[{"type":"API","name":"list","description":"List orders","apiUrl":"/orders","httpMethods":["GET"],"swaggerDescriptions":["Lists orders"],"analysisTarget":{"status":"UNRESOLVED","target":null,"candidates":[],"reasonCode":"TARGET_NOT_FOUND"}}]}]}
                """;
    }
}
