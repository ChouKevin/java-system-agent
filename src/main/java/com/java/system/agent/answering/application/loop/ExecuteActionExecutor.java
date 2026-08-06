package com.java.system.agent.answering.application.loop;

import com.java.system.agent.answering.domain.action.ExecuteAction;
import com.java.system.agent.answering.domain.observation.AgentObservation;
import com.java.system.agent.answering.domain.observation.ObservationCode;
import com.java.system.agent.answering.domain.observation.ObservationId;
import com.java.system.agent.answering.domain.observation.ObservationSource;
import com.java.system.agent.answering.domain.run.AgentEvent;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.RunFailureReason;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.port.in.AnswerExecutionContractException;
import com.java.system.agent.answering.port.in.AnswerExecutionContractFailure;
import com.java.system.agent.answering.port.out.AnalysisCancellationPort;
import com.java.system.agent.answering.port.out.HttpMutationResult;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 執行已驗證 EXECUTE preview action 並保留後續規劃所需 observation 的 application 元件
 */
final class ExecuteActionExecutor {

    static final String NOT_IMPLEMENTED_OBSERVATION = "HTTP mutation execution is not implemented";

    private final AgentLoopTelemetry telemetry;
    private final AnalysisCancellationPort cancellationPort;
    private final AgentRunTransitions transitions;
    private final TerminalResponseCoordinator terminalResponseCoordinator;

    ExecuteActionExecutor(
            AgentLoopTelemetry telemetry,
            AnalysisCancellationPort cancellationPort,
            AgentRunTransitions transitions,
            TerminalResponseCoordinator terminalResponseCoordinator) {
        this.telemetry = Objects.requireNonNull(telemetry, "agent loop telemetry must not be null");
        this.cancellationPort = Objects.requireNonNull(cancellationPort, "analysis cancellation port must not be null");
        this.transitions = Objects.requireNonNull(transitions, "agent run transitions must not be null");
        this.terminalResponseCoordinator = Objects.requireNonNull(
                terminalResponseCoordinator, "terminal response coordinator must not be null");
    }

    ActionLaneOutcome execute(
            AgentLoopRequest request,
            AgentRunState currentState,
            ExecuteAction action,
            int attemptSequence) {
        AgentRunState state = transitions.apply(currentState, new AgentEvent.ActionAccepted(
                currentState.runId(),
                currentState.currentAttempt().attemptId(),
                currentState.stateRevision(),
                action));
        state = transitions.apply(state, new AgentEvent.ExecuteBudgetConsumed(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision()));
        if (cancellationPort.isCancellationRequested(request.runId())) {
            return new ActionLaneOutcome.Terminal(terminalResponseCoordinator.conclude(
                    state,
                    RunOutcome.CANCELLED,
                    TerminalResponseCoordinator.CANCELLED_RESPONSE,
                    Optional.empty(),
                    Optional.empty()));
        }
        HttpMutationResult result;
        try {
            result = Objects.requireNonNull(
                    telemetry.executeMutation(state, action), "HTTP mutation port must return a result");
        } catch (RuntimeException exception) {
            AnswerExecutionContractException mutationFailure = new AnswerExecutionContractException(
                    AnswerExecutionContractFailure.HTTP_MUTATION_CONTRACT,
                    "HTTP mutation contract failed",
                    exception);
            terminalResponseCoordinator.concludeIntegrationFailure(
                    state, mutationFailure, Optional.of(RunFailureReason.HTTP_MUTATION_CONTRACT));
            throw mutationFailure;
        }
        if (result instanceof HttpMutationResult.NotImplemented) {
            AgentObservation observation = new AgentObservation(
                    nextObservationId(state),
                    ObservationSource.HTTP_MUTATION,
                    ObservationCode.EXECUTION_NOT_IMPLEMENTED,
                    NOT_IMPLEMENTED_OBSERVATION,
                    Set.of(),
                    Set.of(),
                    "http-mutation-port");
            state = transitions.apply(state, new AgentEvent.ObservationRecorded(
                    state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), observation));
            return new ActionLaneOutcome.Continue(state, attemptSequence, Optional.empty());
        }
        throw new IllegalStateException("HTTP mutation port result is unavailable");
    }

    private ObservationId nextObservationId(AgentRunState state) {
        return new ObservationId(
                state.currentAttempt().attemptId().value()
                        + ":O"
                        + (state.currentAttempt().observations().size() + 1));
    }
}
