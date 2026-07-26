package com.java.system.agent.runtime.application.state;

import com.java.system.agent.runtime.domain.run.AgentRunState;
import com.java.system.agent.runtime.domain.run.AgentRunStatus;
import com.java.system.agent.runtime.domain.run.AgentEvent;
import com.java.system.agent.runtime.domain.run.AgentTransition;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.run.RunAttempt;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.run.RunRequestIdentity;
import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.answer.AnswerDisposition;
import com.java.system.agent.runtime.domain.answer.AnswerVerdict;
import com.java.system.agent.runtime.domain.action.ClarifyAction;
import com.java.system.agent.runtime.domain.answer.AnswerStatement;
import com.java.system.agent.runtime.domain.answer.StatementId;
import com.java.system.agent.runtime.domain.answer.StatementType;
import com.java.system.agent.runtime.domain.capability.CapabilityDescriptor;
import com.java.system.agent.runtime.domain.capability.CapabilityQuerySchema;
import com.java.system.agent.runtime.domain.candidate.CandidateKind;
import com.java.system.agent.runtime.domain.handle.CapabilityHandle;
import com.java.system.agent.runtime.domain.handle.HandleBinding;
import com.java.system.agent.runtime.domain.observation.AgentObservation;
import com.java.system.agent.runtime.domain.observation.ObservationCode;
import com.java.system.agent.runtime.domain.observation.ObservationId;
import com.java.system.agent.runtime.domain.observation.ObservationSource;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import com.java.system.agent.runtime.domain.conversation.SessionId;
import com.java.system.agent.runtime.domain.conversation.ConversationTurn;
import com.java.system.agent.runtime.domain.conversation.ConversationTurnType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentStateReducerTest {

    private final AgentStateReducer reducer = new AgentStateReducer();

    @Test
    void should_increment_revision_once_and_preserve_action_counters_per_event() {
        AgentRunState initial = initialState();
        AgentRunState started = reducer.reduce(initial,
                new AgentEvent.RunStarted(initial.runId(), initial.currentAttempt().attemptId(), 0)).candidateState();
        AgentRunState attemptStarted = reducer.reduce(started,
                new AgentEvent.AttemptStarted(started.runId(), started.currentAttempt().attemptId(), 1,
                        started.currentAttempt())).candidateState();
        AgentRunState queryBudgetConsumed = reducer.reduce(attemptStarted,
                new AgentEvent.QueryBudgetConsumed(attemptStarted.runId(), attemptStarted.currentAttempt().attemptId(), 2))
                .candidateState();

        assertThat(started.stateRevision()).isEqualTo(1);
        assertThat(attemptStarted.stateRevision()).isEqualTo(2);
        assertThat(queryBudgetConsumed.stateRevision()).isEqualTo(3);
        assertThat(queryBudgetConsumed.acceptedActionCount()).isZero();
        assertThat(queryBudgetConsumed.rejectedActionCount()).isZero();
        assertThat(queryBudgetConsumed.budget().usedSemanticQueries()).isEqualTo(1);
    }

    @Test
    void should_reject_stale_event_and_events_for_another_current_attempt() {
        AgentRunState state = startedState();

        assertThatThrownBy(() -> reducer.reduce(state,
                new AgentEvent.QueryBudgetConsumed(state.runId(), state.currentAttempt().attemptId(), 0)))
                .isInstanceOf(StaleAgentStateRevisionException.class);
        assertThatThrownBy(() -> reducer.reduce(state,
                new AgentEvent.QueryBudgetConsumed(state.runId(), new AnalysisAttemptId("other"),
                        state.stateRevision())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_replace_attempt_only_after_invalidation_and_reset_issued_handles() {
        AgentRunState started = startedState();
        AgentRunState restarting = reducer.reduce(started,
                new AgentEvent.AttemptInvalidated(started.runId(), started.currentAttempt().attemptId(),
                        started.stateRevision(), "revision changed", true)).candidateState();
        RunAttempt restartedAttempt = RunAttempt.empty(new AnalysisAttemptId("attempt-2"));

        AgentRunState restarted = reducer.reduce(restarting,
                new AgentEvent.AttemptStarted(restarting.runId(), restarting.currentAttempt().attemptId(),
                        restarting.stateRevision(), restartedAttempt)).candidateState();

        assertThat(restarting.status()).isEqualTo(AgentRunStatus.RESTARTING);
        assertThat(restarted.status()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(restarted.currentAttempt()).isEqualTo(restartedAttempt);
        assertThat(restarted.attemptSequence()).isEqualTo(2);
        assertThat(restarted.currentAttempt().issuedCandidates()).isEmpty();
        assertThat(restarted.currentAttempt().issuedEvidence()).isEmpty();
    }

    @Test
    void should_allow_terminal_context_discard_without_consuming_exhausted_restart_budget() {
        AgentRunState initial = AgentRunState.initial(
                new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"),
                new AttemptBudget(3, 0, 3, 0, 3, 0, 1, 1, 1, 0),
                new RunRequestIdentity("session-1", "question"));
        AgentRunState runStarted = reducer.reduce(initial, new AgentEvent.RunStarted(
                initial.runId(), initial.currentAttempt().attemptId(), initial.stateRevision())).candidateState();
        AgentRunState started = reducer.reduce(runStarted, new AgentEvent.AttemptStarted(
                runStarted.runId(),
                runStarted.currentAttempt().attemptId(),
                runStarted.stateRevision(),
                runStarted.currentAttempt())).candidateState();

        AgentRunState restarting = reducer.reduce(started, new AgentEvent.AttemptInvalidated(
                started.runId(),
                started.currentAttempt().attemptId(),
                started.stateRevision(),
                "discard stale context before final response",
                false)).candidateState();

        assertThat(restarting.status()).isEqualTo(AgentRunStatus.RESTARTING);
        assertThat(restarting.budget().usedRevisionRestarts()).isEqualTo(1);
    }

    @Test
    void should_reject_restarted_attempt_context_bound_to_another_run() {
        AgentRunState started = startedState();
        AgentRunState restarting = reducer.reduce(started,
                new AgentEvent.AttemptInvalidated(started.runId(), started.currentAttempt().attemptId(),
                        started.stateRevision(), "revision changed", true)).candidateState();
        AnalysisAttemptId newAttemptId = new AnalysisAttemptId("attempt-2");
        CapabilityHandle foreignHandle = new CapabilityHandle("capability-1",
                new HandleBinding(new AnalysisRunId("other-run"), newAttemptId, RevisionVector.empty()));
        RunAttempt foreignContext = new RunAttempt(newAttemptId, RevisionVector.empty(),
                Map.of(foreignHandle, capability()), Map.of(), Map.of(), Map.of());

        assertThatThrownBy(() -> reducer.reduce(restarting,
                new AgentEvent.AttemptStarted(restarting.runId(), restarting.currentAttempt().attemptId(),
                        restarting.stateRevision(), foreignContext)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another run");
    }

    @Test
    void should_clear_a_pending_terminal_response_when_the_attempt_is_invalidated() {
        AgentRunState started = startedState();
        AgentRunState answerPending = reducer.reduce(started,
                new AgentEvent.AnswerAccepted(started.runId(), started.currentAttempt().attemptId(),
                        started.stateRevision(), document(), acceptedCompleteVerdict(), answerSessionId(),
                        answerTurn(started.runId(), document()), true))
                .candidateState();

        AgentRunState restarting = reducer.reduce(answerPending,
                new AgentEvent.AttemptInvalidated(answerPending.runId(), answerPending.currentAttempt().attemptId(),
                        answerPending.stateRevision(), "revision changed", true)).candidateState();

        assertThat(restarting.status()).isEqualTo(AgentRunStatus.RESTARTING);
        assertThat(restarting.pendingTerminalResponse()).isEmpty();
        assertThatThrownBy(() -> conclude(restarting, RunOutcome.COMPLETED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("answer");
        assertThatThrownBy(() -> conclude(restarting, RunOutcome.INCONCLUSIVE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("terminal response");
    }

    @Test
    void should_reject_double_conclusion() {
        AgentRunState state = startedState();
        AgentRunState concluded = reducer.reduce(state,
                new AgentEvent.RunConcluded(state.runId(), state.currentAttempt().attemptId(), state.stateRevision(),
                        RunOutcome.FAILED, true)).candidateState();

        assertThatThrownBy(() -> reducer.reduce(concluded,
                new AgentEvent.RunConcluded(concluded.runId(), concluded.currentAttempt().attemptId(),
                        concluded.stateRevision(), RunOutcome.FAILED, true)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_require_an_accepted_terminal_response_for_completed_or_inconclusive_conclusion() {
        AgentRunState noResponse = startedState();
        AgentRunState clarificationState = startedState();
        AgentRunState clarificationPending = reducer.reduce(clarificationState,
                clarificationAccepted(clarificationState, true)).candidateState();

        assertThatThrownBy(() -> conclude(noResponse, RunOutcome.COMPLETED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("answer");
        assertThatThrownBy(() -> conclude(clarificationPending, RunOutcome.COMPLETED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected");
        assertThatThrownBy(() -> conclude(noResponse, RunOutcome.INCONCLUSIVE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("terminal response");
    }

    @Test
    void should_allow_failed_or_cancelled_conclusion_without_a_pending_terminal_response() {
        AgentRunState failed = conclude(startedState(), RunOutcome.FAILED).candidateState();
        AgentRunState cancelled = conclude(startedState(), RunOutcome.CANCELLED).candidateState();

        assertThat(failed.status()).isEqualTo(AgentRunStatus.CONCLUDED);
        assertThat(failed.pendingTerminalResponse()).isEmpty();
        assertThat(cancelled.status()).isEqualTo(AgentRunStatus.CONCLUDED);
        assertThat(cancelled.pendingTerminalResponse()).isEmpty();
    }

    @Test
    void should_charge_action_rejection_or_final_reserve_by_rejection_mode() {
        AgentRunState normalState = startedState();
        AgentRunState normal = reducer.reduce(normalState,
                new AgentEvent.ActionRejected(normalState.runId(), normalState.currentAttempt().attemptId(),
                        normalState.stateRevision(), Optional.empty(), "proposal could not be parsed", false))
                .candidateState();
        AgentRunState finalState = startedState();
        AgentRunState terminal = reducer.reduce(finalState,
                new AgentEvent.ActionRejected(finalState.runId(), finalState.currentAttempt().attemptId(),
                        finalState.stateRevision(), Optional.empty(), "final proposal could not be parsed", true))
                .candidateState();

        assertThat(normal.budget().usedActionRejections()).isEqualTo(1);
        assertThat(normal.budget().usedFinalAnswers()).isZero();
        assertThat(terminal.budget().usedActionRejections()).isZero();
        assertThat(terminal.budget().usedFinalAnswers()).isEqualTo(1);
        assertThat(terminal.rejectedActionCount()).isEqualTo(1);
    }

    @Test
    void should_charge_answer_and_clarification_by_final_response_mode() {
        AgentRunState normalAnswerState = startedState();
        AgentRunState normalAnswer = reducer.reduce(normalAnswerState,
                answerAccepted(normalAnswerState, AnswerDisposition.ACCEPTED_COMPLETE, false)).candidateState();
        AgentRunState finalAnswerState = startedState();
        AgentRunState finalAnswer = reducer.reduce(finalAnswerState,
                answerAccepted(finalAnswerState, AnswerDisposition.ACCEPTED_INCONCLUSIVE, true)).candidateState();
        AgentRunState normalClarificationState = startedState();
        AgentRunState normalClarification = reducer.reduce(normalClarificationState,
                clarificationAccepted(normalClarificationState, false)).candidateState();
        AgentRunState finalClarificationState = startedState();
        AgentRunState finalClarification = reducer.reduce(finalClarificationState,
                clarificationAccepted(finalClarificationState, true)).candidateState();

        assertThat(normalAnswer.budget().usedAgentSteps()).isEqualTo(1);
        assertThat(normalAnswer.budget().usedFinalAnswers()).isZero();
        assertThat(normalAnswer.acceptedActionCount()).isEqualTo(1);
        assertThat(finalAnswer.budget().usedAgentSteps()).isZero();
        assertThat(finalAnswer.budget().usedFinalAnswers()).isEqualTo(1);
        assertThat(finalAnswer.acceptedActionCount()).isEqualTo(1);
        assertThat(normalClarification.budget().usedAgentSteps()).isEqualTo(1);
        assertThat(normalClarification.budget().usedFinalAnswers()).isZero();
        assertThat(normalClarification.acceptedActionCount()).isEqualTo(1);
        assertThat(finalClarification.budget().usedAgentSteps()).isZero();
        assertThat(finalClarification.budget().usedFinalAnswers()).isEqualTo(1);
        assertThat(finalClarification.acceptedActionCount()).isEqualTo(1);
    }

    @Test
    void should_require_the_pending_response_expected_outcome_when_concluding() {
        AgentRunState answerState = startedState();
        AgentRunState completeAnswer = reducer.reduce(answerState,
                answerAccepted(answerState, AnswerDisposition.ACCEPTED_COMPLETE, true)).candidateState();
        AgentRunState clarificationState = startedState();
        AgentRunState clarification = reducer.reduce(clarificationState,
                clarificationAccepted(clarificationState, true)).candidateState();

        assertThatThrownBy(() -> conclude(completeAnswer, RunOutcome.INCONCLUSIVE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected");
        assertThatThrownBy(() -> conclude(clarification, RunOutcome.COMPLETED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected");
    }

    @Test
    void should_retain_accepted_terminal_content_and_reject_runtime_fixed_conclusions() {
        AgentRunState answerState = startedState();
        AgentRunState answerPending = reducer.reduce(answerState,
                answerAccepted(answerState, AnswerDisposition.ACCEPTED_INCONCLUSIVE, true)).candidateState();
        AgentRunState clarificationState = startedState();
        AgentRunState clarificationPending = reducer.reduce(clarificationState,
                clarificationAccepted(clarificationState, true)).candidateState();

        assertThatThrownBy(() -> reducer.reduce(answerPending,
                new AgentEvent.RunConcluded(answerPending.runId(), answerPending.currentAttempt().attemptId(),
                        answerPending.stateRevision(), RunOutcome.INCONCLUSIVE, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot discard");
        assertThatThrownBy(() -> reducer.reduce(clarificationPending,
                new AgentEvent.RunConcluded(clarificationPending.runId(), clarificationPending.currentAttempt().attemptId(),
                        clarificationPending.stateRevision(), RunOutcome.INCONCLUSIVE, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot discard");

        AgentRunState concluded = conclude(clarificationPending, RunOutcome.INCONCLUSIVE).candidateState();

        assertThat(concluded.pendingTerminalResponse()).isEqualTo(clarificationPending.pendingTerminalResponse());
    }

    @Test
    void should_reject_terminal_turns_that_do_not_match_persisted_request_identity() {
        AgentRunState state = startedState();
        ConversationTurn wrongQuestion = new ConversationTurn(
                state.runId(), "other question", document().renderParagraphs(), ConversationTurnType.ANSWER);
        AgentEvent.AnswerAccepted event = new AgentEvent.AnswerAccepted(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), document(),
                acceptedCompleteVerdict(), answerSessionId(), wrongQuestion, true);

        assertThatThrownBy(() -> reducer.reduce(state, event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request identity");
    }

    @Test
    void should_replace_the_complete_observation_context_on_reissue() {
        AgentRunState started = startedState();
        AgentObservation first = observation("observation-1");
        AgentRunState firstContext = reducer.reduce(started,
                contextIssued(started, Map.of(first.id(), first))).candidateState();
        AgentObservation replacement = observation("observation-2");
        AgentRunState rebound = reducer.reduce(firstContext,
                contextIssued(firstContext, Map.of(replacement.id(), replacement))).candidateState();

        assertThat(rebound.currentAttempt().observations()).containsExactlyEntriesOf(Map.of(replacement.id(), replacement));
    }

    private AgentTransition conclude(AgentRunState state, RunOutcome outcome) {
        return reducer.reduce(state,
                new AgentEvent.RunConcluded(state.runId(), state.currentAttempt().attemptId(), state.stateRevision(),
                        outcome, outcome == RunOutcome.FAILED || outcome == RunOutcome.CANCELLED));
    }

    private AgentEvent.ContextIssued contextIssued(AgentRunState state,
                                                    Map<ObservationId, AgentObservation> observations) {
        return new AgentEvent.ContextIssued(state.runId(), state.currentAttempt().attemptId(), state.stateRevision(),
                state.currentAttempt().revisionVector(), Map.of(), Map.of(), Map.of(), observations);
    }

    private AgentEvent.AnswerAccepted answerAccepted(AgentRunState state, AnswerDisposition disposition,
                                                      boolean finalResponseMode) {
        return new AgentEvent.AnswerAccepted(state.runId(), state.currentAttempt().attemptId(), state.stateRevision(),
                document(), answerVerdict(disposition), answerSessionId(), answerTurn(state.runId(), document()),
                finalResponseMode);
    }

    private AgentEvent.ClarificationAccepted clarificationAccepted(AgentRunState state, boolean finalResponseMode) {
        return new AgentEvent.ClarificationAccepted(state.runId(), state.currentAttempt().attemptId(),
                state.stateRevision(), new ClarifyAction("which repository", List.of(), "scope is ambiguous"),
                answerSessionId(), new ConversationTurn(state.runId(), "question", "which repository",
                ConversationTurnType.CLARIFICATION), finalResponseMode);
    }

    private AnswerVerdict acceptedCompleteVerdict() {
        return answerVerdict(AnswerDisposition.ACCEPTED_COMPLETE);
    }

    private AnswerVerdict answerVerdict(AnswerDisposition disposition) {
        return new AnswerVerdict(disposition, List.of(), List.of(), List.of(), List.of());
    }

    private AnswerDocument document() {
        return new AnswerDocument(List.of(new AnswerStatement(
                new StatementId("statement-1"), StatementType.QUESTION, "answer", Optional.empty(), Set.of(), Set.of())));
    }

    private SessionId answerSessionId() {
        return new SessionId("session-1");
    }

    private ConversationTurn answerTurn(AnalysisRunId runId, AnswerDocument document) {
        return new ConversationTurn(runId, "question", document.renderParagraphs(), ConversationTurnType.ANSWER);
    }

    private AgentObservation observation(String id) {
        return new AgentObservation(new ObservationId(id), ObservationSource.RUNTIME, ObservationCode.UNRESOLVED_CALL,
                "runtime observation", Set.of(), Set.of(), "state reducer test");
    }

    private AgentRunState initialState() {
        return AgentRunState.initial(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"), budget(),
                new RunRequestIdentity("session-1", "question"));
    }

    private AgentRunState startedState() {
        AgentRunState initial = initialState();
        AgentRunState runStarted = reducer.reduce(initial,
                new AgentEvent.RunStarted(initial.runId(), initial.currentAttempt().attemptId(), initial.stateRevision()))
                .candidateState();
        return reducer.reduce(runStarted, new AgentEvent.AttemptStarted(runStarted.runId(),
                runStarted.currentAttempt().attemptId(), runStarted.stateRevision(), runStarted.currentAttempt()))
                .candidateState();
    }

    private AttemptBudget budget() {
        return new AttemptBudget(3, 0, 3, 0, 3, 0, 3, 0, 1, 0);
    }

    private CapabilityDescriptor capability() {
        return new CapabilityDescriptor("find", "v1", Set.of(CandidateKind.REPOSITORY), 0, 1,
                new CapabilityQuerySchema(List.of()));
    }
}
