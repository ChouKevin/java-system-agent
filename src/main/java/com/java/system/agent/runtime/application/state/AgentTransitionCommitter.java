package com.java.system.agent.runtime.application.state;

import com.java.system.agent.runtime.domain.run.AgentRunState;
import com.java.system.agent.runtime.domain.run.AgentBootstrap;
import com.java.system.agent.runtime.domain.run.AgentEvent;
import com.java.system.agent.runtime.domain.run.AgentTransition;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.RunAttempt;
import com.java.system.agent.runtime.port.out.AgentTransitionPort;
import com.java.system.agent.runtime.port.out.AgentTransitionConflictException;
import com.java.system.agent.runtime.port.out.TerminalAcceptanceCancelledException;

import java.util.Objects;
import java.util.Optional;

/**
 * 先 append event 再採用完全相同候選 Agent Run 狀態的提交協調者
 */
public final class AgentTransitionCommitter {

    private final AgentStateReducer reducer;
    private final AgentTransitionPort transitionPort;

    public AgentTransitionCommitter(AgentStateReducer reducer,
                                    AgentTransitionPort transitionPort) {
        this.reducer = Objects.requireNonNull(reducer, "agent state reducer must not be null");
        this.transitionPort = Objects.requireNonNull(transitionPort, "agent transition port must not be null");
    }

    public AgentRunState apply(AgentRunState currentState, AgentEvent event) {
        Objects.requireNonNull(currentState, "current agent run state must not be null");
        Objects.requireNonNull(event, "agent event must not be null");
        if (event instanceof AgentEvent.RunStarted
                || event instanceof AgentEvent.AttemptStarted && currentState.stateRevision() == 1) {
            throw new IllegalArgumentException("initial bootstrap events must be committed atomically");
        }
        AgentTransition transition = reduce(currentState, event);
        try {
            AgentRunState committedState = transitionPort.commit(transition);
            return requireExactCandidate(transition, committedState);
        } catch (AgentTransitionCommitException | AgentTransitionConflictException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AgentTransitionCommitException("agent transition could not be committed", exception);
        }
    }

    /**
     * 建構並以單一原子邊界提交首次 run、attempt 與 context transition
     */
    public AgentRunState bootstrap(AgentRunState initialState, RunAttempt initialContext) {
        Objects.requireNonNull(initialState, "initial agent run state must not be null");
        Objects.requireNonNull(initialContext, "initial run context must not be null");
        AgentTransition runStarted = reduce(initialState, new AgentEvent.RunStarted(
                initialState.runId(), initialState.currentAttempt().attemptId(), initialState.stateRevision()));
        AgentRunState startedState = runStarted.candidateState();
        AgentTransition attemptStarted = reduce(startedState, new AgentEvent.AttemptStarted(
                startedState.runId(), startedState.currentAttempt().attemptId(), startedState.stateRevision(),
                startedState.currentAttempt()));
        AgentRunState attemptState = attemptStarted.candidateState();
        AgentTransition contextIssued = reduce(attemptState, new AgentEvent.ContextIssued(
                attemptState.runId(), attemptState.currentAttempt().attemptId(), attemptState.stateRevision(),
                initialContext.revisionVector(), initialContext.issuedCapabilities(), initialContext.issuedCandidates(),
                initialContext.issuedEvidence(), initialContext.observations()));
        AgentBootstrap bootstrap = new AgentBootstrap(runStarted, attemptStarted, contextIssued);
        try {
            AgentRunState committedState = transitionPort.bootstrap(bootstrap);
            return requireExactCandidate(bootstrap.finalTransition(), committedState);
        } catch (AgentTransitionCommitException | AgentTransitionConflictException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AgentTransitionCommitException("agent run bootstrap could not be committed", exception);
        }
    }

    /**
     * 僅以 durable cancellation 仲裁提交已接受的 terminal response
     */
    public AgentRunState applyTerminalAcceptance(AgentRunState currentState, AgentEvent event) {
        Objects.requireNonNull(currentState, "current agent run state must not be null");
        Objects.requireNonNull(event, "agent event must not be null");
        if (!(event instanceof AgentEvent.AnswerAccepted) && !(event instanceof AgentEvent.ClarificationAccepted)) {
            throw new IllegalArgumentException("terminal acceptance commit requires an accepted terminal event");
        }
        AgentTransition transition = reduce(currentState, event);
        try {
            AgentRunState committedState = transitionPort.commitTerminalAcceptance(transition);
            return requireExactCandidate(transition, committedState);
        } catch (AgentTransitionCommitException | AgentTransitionConflictException
                | TerminalAcceptanceCancelledException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AgentTransitionCommitException("terminal acceptance could not be committed", exception);
        }
    }

    private AgentTransition reduce(AgentRunState currentState, AgentEvent event) {
        return Objects.requireNonNull(reducer.reduce(currentState, event),
                "agent state reducer must return a transition");
    }

    private AgentRunState requireExactCandidate(AgentTransition transition, AgentRunState committedState) {
        if (Objects.isNull(committedState)) {
            throw new AgentTransitionCommitException("agent transition commit returned no candidate state");
        }
        if (!transition.candidateState().equals(committedState)) {
            throw new AgentTransitionCommitException("agent transition commit returned a different candidate state");
        }
        return committedState;
    }

    /**
     * 讀取已持久化 state 並拒絕與查詢 run 不一致的 adapter 回應
     */
    public Optional<AgentRunState> findByRunId(AnalysisRunId runId) {
        Objects.requireNonNull(runId, "analysis run ID must not be null");
        try {
            Optional<AgentRunState> persisted = Objects.requireNonNull(
                    transitionPort.findByRunId(runId), "agent transition port must return an optional state");
            if (persisted.isPresent() && !runId.equals(persisted.orElseThrow().runId())) {
                throw new AgentTransitionCommitException("agent transition lookup returned another run state");
            }
            return persisted;
        } catch (AgentTransitionCommitException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AgentTransitionCommitException("agent run state could not be read", exception);
        }
    }
}
