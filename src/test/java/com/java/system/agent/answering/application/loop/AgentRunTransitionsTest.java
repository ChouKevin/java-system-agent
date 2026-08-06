package com.java.system.agent.answering.application.loop;

import com.java.system.agent.answering.application.state.AgentStateReducer;
import com.java.system.agent.answering.application.state.AgentTransitionCommitter;
import com.java.system.agent.answering.domain.answer.AnswerAcceptance;
import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.AnswerVerificationMode;
import com.java.system.agent.answering.domain.answer.StatementId;
import com.java.system.agent.answering.domain.answer.StatementType;
import com.java.system.agent.answering.domain.conversation.ConversationTurn;
import com.java.system.agent.answering.domain.conversation.ConversationTurnType;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.conversation.SessionId;
import com.java.system.agent.answering.domain.observation.AgentObservation;
import com.java.system.agent.answering.domain.observation.ObservationCode;
import com.java.system.agent.answering.domain.observation.ObservationSource;
import com.java.system.agent.answering.domain.run.AgentBootstrap;
import com.java.system.agent.answering.domain.run.AgentEvent;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.AgentTransition;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.PendingAnswerVerification;
import com.java.system.agent.answering.domain.run.RunAttempt;
import com.java.system.agent.answering.domain.run.RunRequestIdentity;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.out.AgentTransitionConflictException;
import com.java.system.agent.answering.port.out.AgentTransitionPort;
import com.java.system.agent.answering.port.out.TerminalAcceptanceCancelledException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AgentRunTransitions 提交例外與 observation 事件的行為測試
 */
class AgentRunTransitionsTest {

    @Test
    void wrapsApplyCommitFailureWithExistingLoopContext() {
        AgentRunTransitions successfulTransitions = new AgentRunTransitions(new AgentTransitionCommitter(
                new AgentStateReducer(), new RecordingTransitionPort()));
        AgentRunState state = runningState(successfulTransitions);
        AgentEvent event = runtimeEvent(state);
        AgentRunTransitions transitions = new AgentRunTransitions(new AgentTransitionCommitter(
                new AgentStateReducer(), new FailingTransitionPort()));

        assertThatThrownBy(() -> transitions.apply(state, event))
                .isInstanceOf(AgentLoopException.class)
                .satisfies(throwable -> {
                    AgentLoopException exception = (AgentLoopException) throwable;
                    assertThat(exception).hasMessage("agent loop transition could not be committed");
                    assertThat(exception.lastCommittedState()).isEqualTo(state);
                    assertThat(exception.failedEvent()).isEqualTo(event);
                });
    }

    @Test
    void propagatesBootstrapConflictUnchanged() {
        AgentRunTransitions transitions = new AgentRunTransitions(new AgentTransitionCommitter(
                new AgentStateReducer(), new BootstrapConflictTransitionPort()));
        AgentRunState initial = initialState();

        assertThatThrownBy(() -> transitions.bootstrap(initial, RunAttempt.empty(initial.currentAttempt().attemptId())))
                .isInstanceOf(AgentTransitionConflictException.class)
                .hasMessage("bootstrap conflict");
    }

    @Test
    void propagatesTerminalAcceptanceCancellationUnchanged() {
        AgentRunTransitions transitions = new AgentRunTransitions(new AgentTransitionCommitter(
                new AgentStateReducer(), new TerminalCancelledTransitionPort()));
        AgentRunState state = runningState(transitions);
        AgentRunState proposed = transitions.apply(state, new AgentEvent.AnswerProposed(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(),
                new PendingAnswerVerification(state.currentAttempt().attemptId(), RevisionVector.empty(),
                        answerDocument(), AnswerVerificationMode.CONTRACT_ONLY)));
        AgentEvent acceptance = answerAccepted(proposed);

        assertThatThrownBy(() -> transitions.applyTerminalAcceptance(proposed, acceptance))
                .isInstanceOf(TerminalAcceptanceCancelledException.class)
                .hasMessage("terminal acceptance cancelled");
    }

    @Test
    void recordsObservationEventsWithPreservedSourcesAndSequentialIds() {
        RecordingTransitionPort port = new RecordingTransitionPort();
        AgentRunTransitions transitions = new AgentRunTransitions(new AgentTransitionCommitter(new AgentStateReducer(), port));
        AgentRunState state = runningState(transitions);

        AgentRunState runtimeRecorded = transitions.recordRuntimeObservation(
                state, ObservationCode.ACTION_REJECTED, "runtime rejection", Set.of(), Set.of(), "runtime-source");
        AgentRunState capabilityRecorded = transitions.recordCapabilityFailureObservation(
                runtimeRecorded, ObservationCode.EXECUTION_FAILED, "capability failure", Set.of(), Set.of(),
                "capability-source");

        List<AgentObservation> observations = capabilityRecorded.currentAttempt().observations().values().stream().toList();
        assertThat(observations).extracting(AgentObservation::id)
                .extracting(id -> id.value())
                .containsExactly("attempt-1:O1", "attempt-1:O2");
        assertThat(observations).extracting(AgentObservation::source)
                .containsExactly(ObservationSource.RUNTIME, ObservationSource.CAPABILITY_EXECUTOR);
        assertThat(port.lastEvent()).isInstanceOf(AgentEvent.ObservationRecorded.class);
    }

    private AgentRunState runningState(AgentRunTransitions transitions) {
        AgentRunState initial = initialState();
        return transitions.bootstrap(initial, RunAttempt.empty(initial.currentAttempt().attemptId()));
    }

    private AgentRunState initialState() {
        return AgentRunState.initial(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"),
                new AttemptBudget(3, 0, 2, 0, 1, 0, 2, 0, 1, 0),
                new RunRequestIdentity("session-1", new ParticipantRef("test", "participant-1"), "Question?"));
    }

    private AgentEvent runtimeEvent(AgentRunState state) {
        return new AgentEvent.ActionRejected(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), Optional.empty(), "rejected");
    }

    private AgentEvent answerAccepted(AgentRunState state) {
        ConversationTurn turn = new ConversationTurn(state.runId(), state.requestIdentity().participant(), "Question?",
                "Answer", ConversationTurnType.ANSWER);
        return new AgentEvent.AnswerAccepted(state.runId(), state.currentAttempt().attemptId(), state.stateRevision(),
                answerDocument(), AnswerAcceptance.contractOnly(), new SessionId("session-1"), turn);
    }

    private AnswerDocument answerDocument() {
        return new AnswerDocument(List.of(new AnswerStatement(new StatementId("statement-1"), StatementType.QUESTION,
                "Answer", Optional.empty(), Set.of(), Set.of())));
    }

    private static class RecordingTransitionPort implements AgentTransitionPort {

        private AgentEvent lastEvent;

        @Override
        public AgentRunState bootstrap(AgentBootstrap bootstrap) {
            return bootstrap.finalTransition().candidateState();
        }

        @Override
        public AgentRunState commit(AgentTransition transition) {
            lastEvent = transition.event();
            return transition.candidateState();
        }

        @Override
        public AgentRunState commitTerminalAcceptance(AgentTransition transition) {
            return transition.candidateState();
        }

        @Override
        public Optional<AgentRunState> findByRunId(AnalysisRunId runId) {
            return Optional.empty();
        }

        private AgentEvent lastEvent() {
            return lastEvent;
        }
    }

    private static final class FailingTransitionPort extends RecordingTransitionPort {

        @Override
        public AgentRunState commit(AgentTransition transition) {
            throw new IllegalStateException("persistence unavailable");
        }
    }

    private static final class BootstrapConflictTransitionPort extends RecordingTransitionPort {

        @Override
        public AgentRunState bootstrap(AgentBootstrap bootstrap) {
            throw new AgentTransitionConflictException("bootstrap conflict");
        }
    }

    private static final class TerminalCancelledTransitionPort extends RecordingTransitionPort {

        @Override
        public AgentRunState commitTerminalAcceptance(AgentTransition transition) {
            throw new TerminalAcceptanceCancelledException("terminal acceptance cancelled");
        }
    }
}
