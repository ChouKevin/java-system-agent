package com.java.system.agent.persistence.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.interaction.application.InboxRetryPolicy;
import com.java.system.agent.interaction.application.SessionInboxProcessor;
import com.java.system.agent.interaction.domain.InboxClaim;
import com.java.system.agent.interaction.domain.InboxDeferReason;
import com.java.system.agent.interaction.domain.InboxMessage;
import com.java.system.agent.interaction.domain.InboxMessageId;
import com.java.system.agent.interaction.domain.InboxMessageStatus;
import com.java.system.agent.interaction.domain.InboxProcessingOutcome;
import com.java.system.agent.interaction.domain.FinalInteractionResponse;
import com.java.system.agent.interaction.domain.NormalizedSourceEvent;
import com.java.system.agent.interaction.domain.SessionSourceRef;
import com.java.system.agent.interaction.domain.SourceMessageId;
import com.java.system.agent.interaction.domain.SourcePayloadFingerprintV1;
import com.java.system.agent.interaction.domain.TransportEventId;
import com.java.system.agent.interaction.port.out.InboxIdentityGenerator;
import com.java.system.agent.persistence.document.AgentEventDocumentCodec;
import com.java.system.agent.persistence.document.AgentStateDocumentCodec;
import com.java.system.agent.persistence.jdbc.PostgresAgentTransitionAdapter;
import com.java.system.agent.persistence.jdbc.PostgresSessionAdapter;
import com.java.system.agent.persistence.jdbc.PostgresSessionInboxAdapter;
import com.java.system.agent.persistence.jdbc.PostgresSourceAcceptanceAdapter;
import com.java.system.agent.answering.adapter.fake.FakeAttemptIdGenerator;
import com.java.system.agent.answering.adapter.fake.FakeCancellationAdapter;
import com.java.system.agent.answering.adapter.fake.FakeCapabilityCatalogAdapter;
import com.java.system.agent.answering.adapter.fake.FakeRepositoryCatalogAdapter;
import com.java.system.agent.answering.application.AnalysisApplicationService;
import com.java.system.agent.answering.application.ContextIssuer;
import com.java.system.agent.answering.application.ValidatedAgentLoop;
import com.java.system.agent.answering.application.state.AgentStateReducer;
import com.java.system.agent.answering.application.state.AgentTransitionCommitter;
import com.java.system.agent.answering.application.validation.AgentActionValidator;
import com.java.system.agent.answering.application.validation.AnswerDocumentValidator;
import com.java.system.agent.answering.application.validation.AnswerVerdictValidator;
import com.java.system.agent.answering.domain.action.AnswerAction;
import com.java.system.agent.answering.domain.action.ClarifyAction;
import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.AnswerVerificationMode;
import com.java.system.agent.answering.domain.answer.StatementId;
import com.java.system.agent.answering.domain.answer.StatementType;
import com.java.system.agent.answering.domain.conversation.ConversationTurn;
import com.java.system.agent.answering.domain.conversation.ConversationTurnType;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.conversation.SessionId;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.port.in.AnswerExecutionMode;
import com.java.system.agent.answering.port.in.AnswerExecutionContractException;
import com.java.system.agent.answering.port.in.AnswerExecutionContractFailure;
import com.java.system.agent.answering.port.in.AnswerQuestionCommand;
import com.java.system.agent.answering.port.in.AnswerQuestionResult;
import com.java.system.agent.answering.port.out.AgentActionProposal;
import com.java.system.agent.answering.port.out.AgentActionContractException;
import com.java.system.agent.answering.port.out.AnswerVerificationResult;
import com.java.system.agent.answering.port.out.AnswerVerificationUnavailableException;
import com.java.system.agent.answering.port.out.SessionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgreSQL interrupted inbox claim 的 durable recovery 整合測試
 */
class PostgresTerminalRecoveryIT extends PostgresIntegrationTestSupport {

    private static final Instant NOW = Instant.parse("2030-07-26T10:00:00Z");
    private PostgresSessionInboxAdapter inbox;
    private PostgresSourceAcceptanceAdapter acceptance;
    private JdbcClient jdbcClient;
    private PostgresSessionAdapter sessions;
    private PostgresAgentTransitionAdapter transitions;

    @BeforeEach
    void resetSchema() {
        newFlyway().clean();
        newFlyway().migrate();
        DataSource dataSource = newDataSource();
        TransactionTemplate transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Identities identities = new Identities();
        jdbcClient = JdbcClient.create(dataSource);
        inbox = new PostgresSessionInboxAdapter(jdbcClient, transactionTemplate, identities);
        acceptance = new PostgresSourceAcceptanceAdapter(jdbcClient, transactionTemplate, identities);
        sessions = new PostgresSessionAdapter(jdbcClient, transactionTemplate);
        ObjectMapper objectMapper = new ObjectMapper();
        transitions = new PostgresAgentTransitionAdapter(
                jdbcClient,
                transactionTemplate,
                new AgentStateDocumentCodec(objectMapper),
                new AgentEventDocumentCodec(objectMapper));
    }

    @Test
    void recoversInterruptedCapacityClaimWithoutChangingItsAttemptOrReason() {
        String sourceText = "<@bot> 問題";
        acceptance.accept(new NormalizedSourceEvent(
                "slack", new TransportEventId("event-1"), new SourceMessageId("message-1"),
                new SessionSourceRef("slack", "channel-1:thread-1"), new ParticipantRef("slack", "U123456"),
                sourceText, "問題", SourcePayloadFingerprintV1.fromCanonicalFields(
                        "workspace-1", "channel-1", "message-1", "thread-1", "U123456", sourceText), NOW));
        InboxClaim first = inbox.claimNext(NOW).orElseThrow();
        inbox.deferForCapacity(first, NOW.plusSeconds(10));
        assertThat(inboxStatus(first.message().inboxMessageId())).isEqualTo(InboxMessageStatus.PENDING.name());
        InboxClaim capacity = inbox.claimNext(NOW.plusSeconds(10)).orElseThrow();

        assertThat(inbox.recoverInterrupted(NOW.plusSeconds(11))).isEqualTo(1);
        InboxClaim recovered = inbox.claimNext(NOW.plusSeconds(11)).orElseThrow();

        assertThat(capacity.message().attemptCount()).isEqualTo(1);
        assertThat(recovered.message().attemptCount()).isEqualTo(1);
        assertThat(recovered.message().deferReason()).contains(InboxDeferReason.MODEL_CAPACITY);
    }

    @Test
    void releases_the_next_same_session_message_after_a_terminal_planning_tool_contract_failure() {
        admit("event-planning-first", "message-planning-first", "planning-session", "first question");
        admit("event-planning-second", "message-planning-second", "planning-session", "second question");
        InboxClaim firstClaim = inbox.claimNext(NOW).orElseThrow();
        AtomicInteger failedActionCalls = new AtomicInteger();
        SessionInboxProcessor failingProcessor = new SessionInboxProcessor(
                inbox, planningContractService(failedActionCalls), budget(), InboxRetryPolicy.defaults());

        assertThat(failingProcessor.process(firstClaim, NOW)).isEqualTo(InboxProcessingOutcome.FAILED);
        assertThat(failedActionCalls).hasValue(1);
        assertThat(inboxStatus(firstClaim.message().inboxMessageId())).isEqualTo(InboxMessageStatus.FAILED.name());

        InboxClaim secondClaim = inbox.claimNext(NOW.plusSeconds(1)).orElseThrow();
        AtomicInteger successfulActionCalls = new AtomicInteger();
        SessionInboxProcessor successfulProcessor = new SessionInboxProcessor(
                inbox, clarificationService(successfulActionCalls, sessions), budget(), InboxRetryPolicy.defaults());

        assertThat(secondClaim.message().sourceMessageId()).isEqualTo(new SourceMessageId("message-planning-second"));
        assertThat(successfulProcessor.process(secondClaim, NOW.plusSeconds(1)))
                .isEqualTo(InboxProcessingOutcome.COMPLETED);
        assertThat(successfulActionCalls).hasValue(1);
        assertThat(inboxStatus(secondClaim.message().inboxMessageId())).isEqualTo(InboxMessageStatus.COMPLETED.name());
    }

    @Test
    void reconciles_a_crashed_planning_contract_failure_without_replaying_the_model_or_executor() {
        admit("event-planning-crash", "message-planning-crash", "planning-crash", "crash question");
        InboxClaim interruptedClaim = inbox.claimNext(NOW).orElseThrow();
        AtomicInteger actionCalls = new AtomicInteger();
        AnalysisApplicationService service = planningContractService(actionCalls);

        assertThatThrownBy(() -> service.answer(command(interruptedClaim.message())))
                .isInstanceOf(AnswerExecutionContractException.class)
                .extracting(exception -> ((AnswerExecutionContractException) exception).failure())
                .isEqualTo(AnswerExecutionContractFailure.PLANNING_TOOL_CONTRACT);
        assertThat(actionCalls).hasValue(1);
        assertThat(inboxStatus(interruptedClaim.message().inboxMessageId())).isEqualTo(InboxMessageStatus.PROCESSING.name());

        assertThat(inbox.recoverInterrupted(NOW.plusSeconds(1))).isEqualTo(1);
        InboxClaim recoveredClaim = inbox.claimNext(NOW.plusSeconds(1)).orElseThrow();
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox, service, budget(), InboxRetryPolicy.defaults());

        assertThat(processor.process(recoveredClaim, NOW.plusSeconds(1))).isEqualTo(InboxProcessingOutcome.FAILED);
        assertThat(actionCalls).hasValue(1);
        assertThat(inboxStatus(recoveredClaim.message().inboxMessageId())).isEqualTo(InboxMessageStatus.FAILED.name());
        assertThat(inboxFailureCode(recoveredClaim.message().inboxMessageId())).isEqualTo("PLANNING_TOOL_CONTRACT");
        String recoveredFinalResponse = finalResponse(recoveredClaim.message().inboxMessageId());
        assertThat(recoveredFinalResponse).isEqualTo("RUNTIME_NOTICE:FAILED:處理失敗，請稍後再試");
        assertThat(recoveredFinalResponse).doesNotContain(
                "AnswerExecutionContractException", "PLANNING_TOOL_CONTRACT", "planning registry contract failed", "provider", "tool");
    }

    @Test
    void recoversAfterADurableTerminalCheckpointWithoutRepeatingTheActionOrSessionTurn() {
        admit("event-checkpoint", "message-checkpoint", "checkpoint", "Which repository should I inspect?");
        InboxClaim firstClaim = inbox.claimNext(NOW).orElseThrow();
        AtomicInteger actionCalls = new AtomicInteger();
        AnalysisApplicationService interruptedService = clarificationService(
                actionCalls, new CrashAfterDurableAppendSessionPort(sessions));

        assertThatThrownBy(() -> interruptedService.answer(command(firstClaim.message())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated process interruption");
        assertThat(actionCalls).hasValue(1);
        assertThat(sessionTurns(firstClaim.message().sessionId())).containsExactly(new ConversationTurn(
                firstClaim.message().runId(), firstClaim.message().participant(), firstClaim.message().questionText(),
                "Which repository should I inspect?", ConversationTurnType.CLARIFICATION));
        assertThat(inboxStatus(firstClaim.message().inboxMessageId())).isEqualTo(InboxMessageStatus.PROCESSING.name());

        assertThat(inbox.recoverInterrupted(NOW.plusSeconds(1))).isEqualTo(1);
        InboxClaim recoveredClaim = inbox.claimNext(NOW.plusSeconds(1)).orElseThrow();
        AnswerQuestionResult recovered = clarificationService(actionCalls, sessions).answer(command(
                recoveredClaim.message(), AnswerExecutionMode.TERMINAL_RECONCILIATION, recoveredClaim.message().attemptCount()));
        inbox.completeWithFinal(recoveredClaim, new FinalInteractionResponse(
                recovered.runId(), recovered.outcome(), recovered.responseKind(), recovered.responseText()), NOW.plusSeconds(2));

        assertThat(actionCalls).hasValue(1);
        assertThat(eventCount(firstClaim.message().runId(), "CLARIFICATION_ACCEPTED")).isEqualTo(1L);
        assertThat(eventCount(firstClaim.message().runId(), "RUN_CONCLUDED")).isEqualTo(1L);
        assertThat(sessionTurns(firstClaim.message().sessionId())).hasSize(1);
        assertThat(inboxStatus(firstClaim.message().inboxMessageId())).isEqualTo(InboxMessageStatus.COMPLETED.name());
    }

    @Test
    void exhaustsInterruptedClaimsIntoTerminalReconciliationWithoutExternalExecution() {
        admit("event-exhausted", "message-exhausted", "exhausted", "Which repository should I inspect?");
        inbox.claimNext(NOW).orElseThrow();
        assertThat(inbox.recoverInterrupted(NOW.plusSeconds(1))).isEqualTo(1);
        inbox.claimNext(NOW.plusSeconds(1)).orElseThrow();
        assertThat(inbox.recoverInterrupted(NOW.plusSeconds(2))).isEqualTo(1);
        inbox.claimNext(NOW.plusSeconds(2)).orElseThrow();
        assertThat(inbox.recoverInterrupted(NOW.plusSeconds(3))).isEqualTo(1);
        InboxClaim reconciliationClaim = inbox.claimNext(NOW.plusSeconds(3)).orElseThrow();
        AtomicInteger actionCalls = new AtomicInteger();
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox, clarificationService(actionCalls, sessions), budget(), InboxRetryPolicy.defaults());

        assertThat(reconciliationClaim.message().attemptCount()).isEqualTo(4);
        assertThat(processor.process(reconciliationClaim, NOW.plusSeconds(3))).isEqualTo(InboxProcessingOutcome.FAILED);

        assertThat(actionCalls).hasValue(0);
        assertThat(transitions.findByRunId(reconciliationClaim.message().runId())).isEmpty();
        assertThat(inboxStatus(reconciliationClaim.message().inboxMessageId())).isEqualTo(InboxMessageStatus.FAILED.name());
        assertThat(finalResponse(reconciliationClaim.message().inboxMessageId())).isEqualTo(
                "RUNTIME_NOTICE:FAILED:處理失敗，請稍後再試");
    }

    @Test
    void resumesPendingAnswerVerificationWithoutReplanningOrDuplicatingTheSessionTurn() {
        admit("event-verification", "message-verification", "verification", "Which repository should I inspect?");
        InboxClaim firstClaim = inbox.claimNext(NOW).orElseThrow();
        AtomicInteger actionCalls = new AtomicInteger();
        AtomicInteger verifierCalls = new AtomicInteger();
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox, pendingVerificationService(actionCalls, verifierCalls), budget(), InboxRetryPolicy.defaults());

        assertThat(processor.process(firstClaim, NOW)).isEqualTo(InboxProcessingOutcome.RETRY_SCHEDULED);
        AgentRunState pendingVerification = transitions.findByRunId(firstClaim.message().runId()).orElseThrow();
        assertThat(pendingVerification.pendingAnswerVerification()).isPresent();
        assertThat(actionCalls).hasValue(1);
        assertThat(verifierCalls).hasValue(1);

        InboxClaim recoveredClaim = inbox.claimNext(NOW.plusSeconds(1)).orElseThrow();
        assertThat(processor.process(recoveredClaim, NOW.plusSeconds(1))).isEqualTo(InboxProcessingOutcome.COMPLETED);

        assertThat(actionCalls).hasValue(1);
        assertThat(verifierCalls).hasValue(2);
        assertThat(eventCount(firstClaim.message().runId(), "ANSWER_PROPOSED")).isEqualTo(1L);
        assertThat(sessionTurns(firstClaim.message().sessionId())).containsExactly(new ConversationTurn(
                firstClaim.message().runId(), firstClaim.message().participant(), firstClaim.message().questionText(),
                "Which repository should I inspect?", ConversationTurnType.ANSWER));
        assertThat(inboxStatus(firstClaim.message().inboxMessageId())).isEqualTo(InboxMessageStatus.COMPLETED.name());
    }

    private void admit(String eventId, String messageId, String threadId, String questionText) {
        String sourceText = "<@bot> " + questionText;
        assertThat(acceptance.accept(new NormalizedSourceEvent(
                "slack", new TransportEventId(eventId), new SourceMessageId(messageId),
                new SessionSourceRef("slack", "channel-1:" + threadId), new ParticipantRef("slack", "U123456"),
                sourceText, questionText, SourcePayloadFingerprintV1.fromCanonicalFields(
                        "workspace-1", "channel-1", messageId, threadId, "U123456", sourceText), NOW))
                .admission()).isPresent();
    }

    private AnalysisApplicationService clarificationService(AtomicInteger actionCalls, SessionPort sessionPort) {
        ValidatedAgentLoop loop = ValidatedAgentLoop.compose(
                context -> {
                    actionCalls.incrementAndGet();
                    return new AgentActionProposal.Proposed(
                            new ClarifyAction("Which repository should I inspect?", List.of(), "scope is ambiguous"));
                },
                query -> {
                    throw new AssertionError("clarification must not execute a semantic query");
                },
                (mode, context) -> {
                    throw new AssertionError("clarification must not verify an answer");
                },
                AnswerVerificationMode.LLM,
                sessionPort,
                new FakeRepositoryCatalogAdapter(),
                new FakeCapabilityCatalogAdapter(),
                repositoryId -> {
                    throw new AssertionError("clarification must not resolve a repository revision");
                },
                new FakeCancellationAdapter(),
                new FakeAttemptIdGenerator().register(new AnalysisAttemptId("attempt-clarification")),
                new AgentActionValidator(),
                new AnswerDocumentValidator(),
                new AnswerVerdictValidator(),
                new AgentTransitionCommitter(new AgentStateReducer(), transitions),
                new ContextIssuer());
        return new AnalysisApplicationService(loop);
    }

    private AnalysisApplicationService planningContractService(AtomicInteger actionCalls) {
        ValidatedAgentLoop loop = ValidatedAgentLoop.compose(
                context -> {
                    actionCalls.incrementAndGet();
                    throw new AgentActionContractException("planning registry contract failed", null);
                },
                query -> {
                    throw new AssertionError("planning contract failure must not execute a semantic query");
                },
                (mode, context) -> {
                    throw new AssertionError("planning contract failure must not verify an answer");
                },
                AnswerVerificationMode.LLM,
                sessions,
                new FakeRepositoryCatalogAdapter(),
                new FakeCapabilityCatalogAdapter(),
                repositoryId -> {
                    throw new AssertionError("planning contract failure must not resolve a repository revision");
                },
                new FakeCancellationAdapter(),
                new FakeAttemptIdGenerator().register(new AnalysisAttemptId("attempt-planning-failure")),
                new AgentActionValidator(),
                new AnswerDocumentValidator(),
                new AnswerVerdictValidator(),
                new AgentTransitionCommitter(new AgentStateReducer(), transitions),
                new ContextIssuer());
        return new AnalysisApplicationService(loop);
    }

    private AnalysisApplicationService pendingVerificationService(AtomicInteger actionCalls, AtomicInteger verifierCalls) {
        ValidatedAgentLoop loop = ValidatedAgentLoop.compose(
                context -> {
                    actionCalls.incrementAndGet();
                    return new AgentActionProposal.Proposed(new AnswerAction(new AnswerDocument(List.of(
                            new AnswerStatement(new StatementId("statement-1"), StatementType.QUESTION,
                                    "Which repository should I inspect?", Optional.empty(), Set.of(), Set.of())))));
                },
                query -> {
                    throw new AssertionError("answer proposal must not execute a semantic query");
                },
                (mode, context) -> {
                    if (verifierCalls.incrementAndGet() == 1) {
                        throw new AnswerVerificationUnavailableException("temporary verifier outage", null); // cs-allow
                    }
                    return new AnswerVerificationResult.ContractAccepted();
                },
                AnswerVerificationMode.CONTRACT_ONLY,
                sessions,
                new FakeRepositoryCatalogAdapter(),
                new FakeCapabilityCatalogAdapter(),
                repositoryId -> {
                    throw new AssertionError("answer proposal must not resolve a repository revision");
                },
                new FakeCancellationAdapter(),
                new FakeAttemptIdGenerator().register(new AnalysisAttemptId("attempt-answer")),
                new AgentActionValidator(),
                new AnswerDocumentValidator(),
                new AnswerVerdictValidator(),
                new AgentTransitionCommitter(new AgentStateReducer(), transitions),
                new ContextIssuer());
        return new AnalysisApplicationService(loop);
    }

    private AnswerQuestionCommand command(InboxMessage message) {
        return new AnswerQuestionCommand(
                message.runId(), message.sessionId(), message.participant(), message.questionText(), budget());
    }

    private AnswerQuestionCommand command(InboxMessage message, AnswerExecutionMode mode, int attempt) {
        return new AnswerQuestionCommand(
                message.runId(), message.sessionId(), message.participant(), message.questionText(), budget(), mode, attempt);
    }

    private AttemptBudget budget() {
        return new AttemptBudget(2, 0, 1, 0, 1, 0, 1, 0, 1, 0);
    }

    private List<ConversationTurn> sessionTurns(SessionId sessionId) {
        return sessions.read(sessionId).turns();
    }

    private long eventCount(AnalysisRunId runId, String eventType) {
        return jdbcClient.sql("""
                SELECT COUNT(*)
                FROM agent_run_event
                WHERE run_id = :runId
                  AND event_type = :eventType
                """)
                .param("runId", runId.value())
                .param("eventType", eventType)
                .query(Long.class)
                .single();
    }

    private String inboxStatus(InboxMessageId inboxMessageId) {
        return jdbcClient.sql("""
                SELECT status
                FROM session_inbox
                WHERE inbox_message_id = :inboxMessageId
                """)
                .param("inboxMessageId", inboxMessageId.value())
                .query(String.class)
                .single();
    }

    private String inboxFailureCode(InboxMessageId inboxMessageId) {
        return jdbcClient.sql("""
                SELECT last_error_code
                FROM session_inbox
                WHERE inbox_message_id = :inboxMessageId
                """)
                .param("inboxMessageId", inboxMessageId.value())
                .query(String.class)
                .single();
    }

    private String finalResponse(InboxMessageId inboxMessageId) {
        return jdbcClient.sql("""
                SELECT response_kind || ':' || outcome || ':' || response_text
                FROM delivery_outbox
                WHERE inbox_message_id = :inboxMessageId
                  AND delivery_kind = 'FINAL_RESPONSE'
                """)
                .param("inboxMessageId", inboxMessageId.value())
                .query(String.class)
                .single();
    }

    /**
     * 模擬 session turn 已 durable 而程序在完成 inbox 前中斷的 SessionPort
     */
    private static final class CrashAfterDurableAppendSessionPort implements SessionPort {

        private final SessionPort delegate;
        private boolean crashPending = true;

        private CrashAfterDurableAppendSessionPort(SessionPort delegate) {
            this.delegate = Objects.requireNonNull(delegate, "session port delegate must not be null");
        }

        @Override
        public SessionHistory read(SessionId sessionId) {
            return delegate.read(sessionId);
        }

        @Override
        public void append(SessionId sessionId, ConversationTurn turn) {
            delegate.append(sessionId, turn);
            if (crashPending) {
                crashPending = false;
                throw new IllegalStateException("simulated process interruption");
            }
        }
    }

    private static final class Identities implements InboxIdentityGenerator {

        private int sequence;

        @Override
        public InboxMessageId nextInboxMessageId() {
            return new InboxMessageId("inbox-" + nextSequence());
        }

        @Override
        public SessionId nextSessionId() {
            return new SessionId("session-" + nextSequence());
        }

        @Override
        public AnalysisRunId nextRunId() {
            return new AnalysisRunId("run-" + nextSequence());
        }

        @Override
        public String nextDeliveryId() {
            return "delivery-" + nextSequence();
        }

        @Override
        public String nextConflictId() {
            return "conflict-" + nextSequence();
        }

        private int nextSequence() {
            sequence = Math.incrementExact(sequence);
            return sequence;
        }
    }
}
