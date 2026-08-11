package com.java.system.agent.persistence.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.persistence.document.AgentEventDocumentCodec;
import com.java.system.agent.persistence.document.AgentStateDocumentCodec;
import com.java.system.agent.persistence.jdbc.JdbcPersistenceException;
import com.java.system.agent.persistence.jdbc.PostgresAgentTransitionAdapter;
import com.java.system.agent.persistence.jdbc.PostgresAnalysisCancellationAdapter;
import com.java.system.agent.answering.application.state.AgentStateReducer;
import com.java.system.agent.answering.domain.action.ClarifyAction;
import com.java.system.agent.answering.domain.action.PlanAction;
import com.java.system.agent.answering.domain.conversation.ConversationTurn;
import com.java.system.agent.answering.domain.conversation.ConversationTurnType;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.conversation.SessionId;
import com.java.system.agent.answering.domain.plan.InformationNeed;
import com.java.system.agent.answering.domain.plan.InformationNeedId;
import com.java.system.agent.answering.domain.plan.QuestionPlan;
import com.java.system.agent.answering.domain.run.AgentBootstrap;
import com.java.system.agent.answering.domain.run.AgentEvent;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.AgentTransition;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.RunAttempt;
import com.java.system.agent.answering.domain.run.RunRequestIdentity;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.out.AgentTransitionConflictException;
import com.java.system.agent.answering.port.out.TerminalAcceptanceCancelledException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgresAgentTransitionAdapter 的真實 PostgreSQL atomic snapshot 與 event trace 行為驗證
 */
class PostgresAgentTransitionAdapterIT extends PostgresIntegrationTestSupport {

    private static final ParticipantRef PARTICIPANT = new ParticipantRef("test", "participant-1");

    private JdbcClient jdbcClient;
    private PostgresAgentTransitionAdapter transitions;
    private PostgresAnalysisCancellationAdapter cancellations;
    private AgentStateReducer reducer;

    @BeforeEach
    void resetSchema() {
        newFlyway().clean();
        newFlyway().migrate();
        DataSource dataSource = newDataSource();
        jdbcClient = JdbcClient.create(dataSource);
        TransactionTemplate transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ObjectMapper objectMapper = new ObjectMapper();
        transitions = new PostgresAgentTransitionAdapter(
                jdbcClient,
                transactionTemplate,
                new AgentStateDocumentCodec(objectMapper),
                new AgentEventDocumentCodec(objectMapper));
        cancellations = new PostgresAnalysisCancellationAdapter(jdbcClient);
        reducer = new AgentStateReducer();
    }

    @Test
    void bootstrapsThreeOrderedEventsAndPublishesOnlyTheFinalContextSnapshot() {
        AgentBootstrap bootstrap = bootstrap("run-bootstrap");

        AgentRunState published = transitions.bootstrap(bootstrap);

        assertThat(published).isEqualTo(bootstrap.finalTransition().candidateState());
        assertThat(eventRevisions("run-bootstrap")).containsExactly(1L, 2L, 3L);
        assertThat(eventTypes("run-bootstrap")).containsExactly("RUN_STARTED", "ATTEMPT_STARTED", "CONTEXT_ISSUED");
        assertThat(transitions.findByRunId(new AnalysisRunId("run-bootstrap"))).contains(published);
    }

    @Test
    void rejectsDuplicateBootstrapWithoutChangingEventsOrSnapshot() {
        AgentBootstrap bootstrap = bootstrap("run-duplicate");
        AgentRunState first = transitions.bootstrap(bootstrap);

        assertThatThrownBy(() -> transitions.bootstrap(bootstrap))
                .isInstanceOf(AgentTransitionConflictException.class);

        assertThat(eventRevisions("run-duplicate")).containsExactly(1L, 2L, 3L);
        assertThat(transitions.findByRunId(new AnalysisRunId("run-duplicate"))).contains(first);
    }

    @Test
    void rejectsBootstrapWhoseCandidateStatesDisagreeOnRequestIdentityBeforePersistingAnything() {
        AgentBootstrap bootstrap = bootstrap("run-bootstrap-identity");
        AgentRunState forgedRunStartedState = withRequestIdentity(
                bootstrap.runStarted().candidateState(),
                new RunRequestIdentity(
                        bootstrap.runStarted().candidateState().requestIdentity().sessionIdValue(),
                        PARTICIPANT,
                        "forged bootstrap question"));
        AgentBootstrap forged = new AgentBootstrap(
                new AgentTransition(bootstrap.runStarted().event(), forgedRunStartedState),
                bootstrap.attemptStarted(),
                bootstrap.contextIssued());

        assertThatThrownBy(() -> transitions.bootstrap(forged)).isInstanceOf(IllegalArgumentException.class);

        assertThat(eventRevisions("run-bootstrap-identity")).isEmpty();
        assertThat(transitions.findByRunId(new AnalysisRunId("run-bootstrap-identity"))).isEmpty();
    }

    @Test
    void appendsAnOrdinaryTransitionAndPublishesItsExactCandidateSnapshot() {
        AgentRunState persisted = transitions.bootstrap(bootstrap("run-transition"));
        AgentTransition transition = reducer.reduce(
                persisted,
                new AgentEvent.ActionRejected(persisted.runId(), persisted.currentAttempt().attemptId(),
                        persisted.stateRevision(), Optional.empty(), "MALFORMED_RESPONSE", "action was rejected"));

        AgentRunState published = transitions.commit(transition);

        assertThat(published).isEqualTo(transition.candidateState());
        assertThat(eventRevisions("run-transition")).containsExactly(1L, 2L, 3L, 4L);
        assertThat(transitions.findByRunId(new AnalysisRunId("run-transition"))).contains(published);
    }

    @Test
    void rejectsAStaleTransitionWithoutChangingEventsOrSnapshot() {
        AgentBootstrap bootstrap = bootstrap("run-stale");
        AgentRunState persisted = transitions.bootstrap(bootstrap);
        AgentRunState attemptState = bootstrap.attemptStarted().candidateState();
        AgentTransition stale = reducer.reduce(
                attemptState,
                new AgentEvent.QueryBudgetConsumed(attemptState.runId(), attemptState.currentAttempt().attemptId(),
                        attemptState.stateRevision()));

        assertThatThrownBy(() -> transitions.commit(stale)).isInstanceOf(AgentTransitionConflictException.class);

        assertThat(eventRevisions("run-stale")).containsExactly(1L, 2L, 3L);
        assertThat(transitions.findByRunId(new AnalysisRunId("run-stale"))).contains(persisted);
    }

    @Test
    void rejectsAnOrdinaryCandidateWithAChangedRequestIdentityWithoutMutatingTheSnapshotOrTrace() {
        AgentRunState persisted = transitions.bootstrap(bootstrap("run-forged-identity"));
        AgentTransition transition = reducer.reduce(
                persisted,
                new AgentEvent.ActionRejected(persisted.runId(), persisted.currentAttempt().attemptId(),
                        persisted.stateRevision(), Optional.empty(), "MALFORMED_RESPONSE", "action was rejected"));
        AgentRunState forgedCandidate = withRequestIdentity(
                transition.candidateState(),
                new RunRequestIdentity(
                        transition.candidateState().requestIdentity().sessionIdValue(),
                        PARTICIPANT,
                        "forged ordinary question"));
        AgentTransition forged = new AgentTransition(transition.event(), forgedCandidate);

        assertThatThrownBy(() -> transitions.commit(forged)).isInstanceOf(AgentTransitionConflictException.class);

        assertThat(eventRevisions("run-forged-identity")).containsExactly(1L, 2L, 3L);
        assertThat(transitions.findByRunId(new AnalysisRunId("run-forged-identity"))).contains(persisted);
    }

    @Test
    void commitsARevisionRestartAttemptWithTheNewAttemptAsItsExactCandidateSnapshot() {
        AgentRunState persisted = transitions.bootstrap(bootstrap("run-revision-restart"));
        AgentTransition invalidation = reducer.reduce(
                persisted,
                new AgentEvent.AttemptInvalidated(
                        persisted.runId(),
                        persisted.currentAttempt().attemptId(),
                        persisted.stateRevision(),
                        "repository revision changed",
                        true));
        AgentRunState restarting = transitions.commit(invalidation);
        RunAttempt replacementAttempt = RunAttempt.empty(new AnalysisAttemptId("attempt-revision-restart-2"));
        AgentTransition restart = reducer.reduce(
                restarting,
                new AgentEvent.AttemptStarted(
                        restarting.runId(),
                        restarting.currentAttempt().attemptId(),
                        restarting.stateRevision(),
                        replacementAttempt));

        AgentRunState published = transitions.commit(restart);

        assertThat(published).isEqualTo(restart.candidateState());
        assertThat(published.currentAttempt()).isEqualTo(replacementAttempt);
        assertThat(eventRevisions("run-revision-restart")).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(eventTypes("run-revision-restart"))
                .containsExactly("RUN_STARTED", "ATTEMPT_STARTED", "CONTEXT_ISSUED", "ATTEMPT_INVALIDATED", "ATTEMPT_STARTED");
        assertThat(transitions.findByRunId(persisted.runId())).contains(restart.candidateState());
    }

    @Test
    void rollsBackSnapshotUpdateWhenTheEventInsertFails() {
        AgentRunState persisted = transitions.bootstrap(bootstrap("run-rollback"));
        AgentTransition transition = reducer.reduce(
                persisted,
                new AgentEvent.ActionRejected(persisted.runId(), persisted.currentAttempt().attemptId(),
                        persisted.stateRevision(), Optional.empty(), "MALFORMED_RESPONSE", "action was rejected"));
        jdbcClient.sql("DROP TABLE agent_run_event").update();

        assertThatThrownBy(() -> transitions.commit(transition)).isInstanceOf(JdbcPersistenceException.class);

        assertThat(snapshotRevision("run-rollback")).isEqualTo(3L);
    }

    @Test
    void readsDurableCancellationAndPreventsTerminalAcceptanceWithoutMutatingTheTraceOrSnapshot() {
        AgentRunState persisted = transitions.bootstrap(bootstrap("run-cancelled"));
        AgentRunState planned = persistQuestionPlan(persisted);
        AgentRunState selectedClarification = persistClarificationSelection(planned);
        jdbcClient.sql("""
                UPDATE agent_run
                SET cancellation_requested = TRUE
                WHERE run_id = :runId
                """)
                .param("runId", persisted.runId().value())
                .update();
        AgentTransition terminalAcceptance = reducer.reduce(
                selectedClarification,
                new AgentEvent.ClarificationAccepted(
                        selectedClarification.runId(),
                        selectedClarification.currentAttempt().attemptId(),
                        selectedClarification.stateRevision(),
                        new ClarifyAction("Which repository should be used?", List.of(), "scope is ambiguous"),
                        new SessionId(selectedClarification.requestIdentity().sessionIdValue()),
                        new ConversationTurn(
                                selectedClarification.runId(),
                                PARTICIPANT,
                                selectedClarification.requestIdentity().questionText(),
                                "Which repository should be used?",
                                ConversationTurnType.CLARIFICATION)));

        assertThat(cancellations.isCancellationRequested(persisted.runId())).isTrue();
        assertThat(cancellations.isCancellationRequested(new AnalysisRunId("missing-run"))).isFalse();
        assertThatThrownBy(() -> transitions.commitTerminalAcceptance(terminalAcceptance))
                .isInstanceOf(TerminalAcceptanceCancelledException.class);
        assertThat(eventRevisions("run-cancelled")).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(transitions.findByRunId(new AnalysisRunId("run-cancelled"))).contains(selectedClarification);
    }

    @Test
    void failsClosedWhenARelationalSnapshotProjectionDisagreesWithTheDocument() {
        AgentRunState persisted = transitions.bootstrap(bootstrap("run-tampered-projection"));
        jdbcClient.sql("""
                UPDATE agent_run
                SET question_text = 'tampered relational question'
                WHERE run_id = :runId
                """)
                .param("runId", persisted.runId().value())
                .update();

        assertThatThrownBy(() -> transitions.findByRunId(persisted.runId()))
                .isInstanceOf(JdbcPersistenceException.class)
                .hasMessage("durable persistence operation failed")
                .hasNoCause();
    }

    @Test
    void commitsTerminalAcceptanceWithItsExactCandidateSnapshotAndOneAcceptedEvent() {
        AgentRunState persisted = transitions.bootstrap(bootstrap("run-terminal-success"));
        AgentRunState planned = persistQuestionPlan(persisted);
        AgentRunState selectedClarification = persistClarificationSelection(planned);
        AgentTransition terminalAcceptance = clarificationAcceptance(selectedClarification);

        AgentRunState published = transitions.commitTerminalAcceptance(terminalAcceptance);

        assertThat(published).isEqualTo(terminalAcceptance.candidateState());
        assertThat(eventRevisions("run-terminal-success")).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(eventTypes("run-terminal-success"))
                .containsExactly("RUN_STARTED", "ATTEMPT_STARTED", "CONTEXT_ISSUED", "ACTION_SELECTED",
                        "QUESTION_PLAN_CREATED", "ACTION_SELECTED", "CLARIFICATION_ACCEPTED");
        assertThat(transitions.findByRunId(persisted.runId())).contains(terminalAcceptance.candidateState());
    }

    private AgentBootstrap bootstrap(String runIdValue) {
        AnalysisRunId runId = new AnalysisRunId(runIdValue);
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-" + runIdValue);
        RunRequestIdentity identity = new RunRequestIdentity(
                "session-" + runIdValue, PARTICIPANT, "question-" + runIdValue);
        insertSession(identity.sessionIdValue());
        AgentRunState initial = AgentRunState.initial(
                runId,
                attemptId,
                new AttemptBudget(5, 0, 4, 0, 1, 0, 3, 0, 2, 0),
                identity);
        AgentTransition runStarted = reducer.reduce(initial, new AgentEvent.RunStarted(runId, attemptId, 0));
        AgentTransition attemptStarted = reducer.reduce(
                runStarted.candidateState(),
                new AgentEvent.AttemptStarted(runId, attemptId, 1, runStarted.candidateState().currentAttempt()));
        AgentTransition contextIssued = reducer.reduce(
                attemptStarted.candidateState(),
                new AgentEvent.ContextIssued(
                        runId,
                        attemptId,
                        2,
                        RevisionVector.empty(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of()));
        return new AgentBootstrap(runStarted, attemptStarted, contextIssued);
    }

    private AgentTransition clarificationAcceptance(AgentRunState persisted) {
        return reducer.reduce(
                persisted,
                new AgentEvent.ClarificationAccepted(
                        persisted.runId(),
                        persisted.currentAttempt().attemptId(),
                        persisted.stateRevision(),
                        new ClarifyAction("Which repository should be used?", List.of(), "scope is ambiguous"),
                        new SessionId(persisted.requestIdentity().sessionIdValue()),
                        new ConversationTurn(
                                persisted.runId(),
                                PARTICIPANT,
                                persisted.requestIdentity().questionText(),
                                "Which repository should be used?",
                                ConversationTurnType.CLARIFICATION)));
    }

    private AgentRunState persistQuestionPlan(AgentRunState state) {
        QuestionPlan plan = plan();
        AgentTransition selected = reducer.reduce(state, new AgentEvent.ActionSelected(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), new PlanAction(plan)));
        AgentRunState selectedState = transitions.commit(selected);
        AgentTransition created = reducer.reduce(selectedState, new AgentEvent.QuestionPlanCreated(
                selectedState.runId(), selectedState.currentAttempt().attemptId(), selectedState.stateRevision(), plan));
        return transitions.commit(created);
    }

    private AgentRunState persistClarificationSelection(AgentRunState state) {
        AgentTransition selected = reducer.reduce(state, new AgentEvent.ActionSelected(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(),
                new ClarifyAction("Which repository should be used?", List.of(), "scope is ambiguous")));
        return transitions.commit(selected);
    }

    private QuestionPlan plan() {
        return new QuestionPlan(List.of(new InformationNeed(new InformationNeedId("need-1"), "Trace the route")));
    }

    private AgentRunState withRequestIdentity(AgentRunState state, RunRequestIdentity identity) {
        return new AgentRunState(
                state.runId(),
                state.status(),
                state.currentAttempt(),
                state.attemptSequence(),
                state.budget(),
                state.acceptedActionCount(),
                state.rejectedActionCount(),
                state.stateRevision(),
                state.finalOutcome(),
                state.runtimeNoticeReason(),
                state.failureReason(),
                state.pendingTerminalResponse(),
                state.pendingAnswerVerification(),
                identity,
                state.modelInteractions());
    }

    private void insertSession(String sessionId) {
        jdbcClient.sql("""
                INSERT INTO agent_session (
                    session_id, source_type, source_key, next_inbox_sequence, next_turn_sequence, created_at
                ) VALUES (
                    :sessionId, 'test', :sourceKey, 0, 0, CURRENT_TIMESTAMP
                )
                """)
                .param("sessionId", sessionId)
                .param("sourceKey", sessionId)
                .update();
    }

    private List<Long> eventRevisions(String runId) {
        return jdbcClient.sql("""
                SELECT state_revision
                FROM agent_run_event
                WHERE run_id = :runId
                ORDER BY state_revision
                """)
                .param("runId", runId)
                .query(Long.class)
                .list();
    }

    private List<String> eventTypes(String runId) {
        return jdbcClient.sql("""
                SELECT event_type
                FROM agent_run_event
                WHERE run_id = :runId
                ORDER BY state_revision
                """)
                .param("runId", runId)
                .query(String.class)
                .list();
    }

    private long snapshotRevision(String runId) {
        return jdbcClient.sql("""
                SELECT state_revision
                FROM agent_run
                WHERE run_id = :runId
                """)
                .param("runId", runId)
                .query(Long.class)
                .single();
    }
}
