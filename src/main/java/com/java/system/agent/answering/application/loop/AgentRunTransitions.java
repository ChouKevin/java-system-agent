package com.java.system.agent.answering.application.loop;

import com.java.system.agent.answering.application.state.AgentTransitionCommitException;
import com.java.system.agent.answering.application.state.AgentTransitionCommitter;
import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.handle.EvidenceHandle;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.observation.AgentObservation;
import com.java.system.agent.answering.domain.observation.ObservationCode;
import com.java.system.agent.answering.domain.observation.ObservationId;
import com.java.system.agent.answering.domain.observation.ObservationSource;
import com.java.system.agent.answering.domain.run.AgentEvent;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.AnswerVerificationAbandonReason;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.RunAttempt;
import com.java.system.agent.answering.port.out.AgentTransitionConflictException;
import com.java.system.agent.answering.port.out.TerminalAcceptanceCancelledException;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 將 Agent state transition 經由單一原子提交邊界推進的 application gateway
 */
final class AgentRunTransitions {

    private final AgentTransitionCommitter transitionCommitter;

    AgentRunTransitions(AgentTransitionCommitter transitionCommitter) {
        this.transitionCommitter = Objects.requireNonNull(
                transitionCommitter, "agent transition committer must not be null");
    }

    Optional<AgentRunState> findByRunId(AnalysisRunId runId) {
        return transitionCommitter.findByRunId(runId);
    }

    AgentRunState apply(AgentRunState state, AgentEvent event) {
        try {
            return transitionCommitter.apply(state, event);
        } catch (AgentTransitionCommitException exception) {
            throw new AgentLoopException(
                    "agent loop transition could not be committed",
                    state,
                    event,
                    exception);
        }
    }

    AgentRunState bootstrap(AgentRunState initialState, RunAttempt initialContext) {
        try {
            return transitionCommitter.bootstrap(initialState, initialContext);
        } catch (AgentTransitionConflictException exception) {
            throw exception;
        } catch (AgentTransitionCommitException exception) {
            throw new AgentLoopException(
                    "agent loop bootstrap could not be committed",
                    initialState,
                    new AgentEvent.RunStarted(
                            initialState.runId(), initialState.currentAttempt().attemptId(), initialState.stateRevision()),
                    exception);
        }
    }

    AgentRunState applyTerminalAcceptance(AgentRunState state, AgentEvent event) {
        try {
            return transitionCommitter.applyTerminalAcceptance(state, event);
        } catch (TerminalAcceptanceCancelledException exception) {
            throw exception;
        } catch (AgentTransitionCommitException exception) {
            throw new AgentLoopException("agent loop terminal acceptance could not be committed", state, event, exception);
        }
    }

    AgentRunState commitContext(AgentRunState state, RunAttempt context) {
        return apply(state, new AgentEvent.ContextIssued(
                state.runId(),
                state.currentAttempt().attemptId(),
                state.stateRevision(),
                context.revisionVector(),
                context.issuedCapabilities(),
                context.issuedCandidates(),
                context.issuedEvidence(),
                context.observations()));
    }

    AgentRunState recordRuntimeObservation(
            AgentRunState state,
            ObservationCode code,
            String description,
            Set<CandidateHandle> candidates,
            Set<EvidenceHandle> evidence,
            String provenance) {
        ObservationId id = nextObservationId(state);
        AgentObservation observation = new AgentObservation(
                id,
                ObservationSource.RUNTIME,
                code,
                description,
                candidates,
                evidence,
                provenance);
        return apply(state, new AgentEvent.ObservationRecorded(
                state.runId(),
                state.currentAttempt().attemptId(),
                state.stateRevision(),
                observation));
    }

    AgentRunState recordCapabilityFailureObservation(
            AgentRunState state,
            ObservationCode code,
            String description,
            Set<CandidateHandle> candidates,
            Set<EvidenceHandle> evidence,
            String provenance) {
        ObservationId id = nextObservationId(state);
        AgentObservation observation = new AgentObservation(
                id,
                ObservationSource.CAPABILITY_EXECUTOR,
                code,
                description,
                candidates,
                evidence,
                provenance);
        return apply(state, new AgentEvent.ObservationRecorded(
                state.runId(),
                state.currentAttempt().attemptId(),
                state.stateRevision(),
                observation));
    }

    AgentRunState abandonAnswerVerification(
            AgentRunState state,
            AnswerVerificationAbandonReason reason) {
        return apply(state, new AgentEvent.AnswerVerificationAbandoned(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), reason));
    }

    HandleBinding currentBinding(AgentRunState state) {
        return new HandleBinding(
                state.runId(),
                state.currentAttempt().attemptId(),
                state.currentAttempt().revisionVector());
    }

    private ObservationId nextObservationId(AgentRunState state) {
        return new ObservationId(
                state.currentAttempt().attemptId().value()
                        + ":O"
                        + (state.currentAttempt().observations().size() + 1));
    }
}
