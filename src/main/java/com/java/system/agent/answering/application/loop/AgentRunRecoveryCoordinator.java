package com.java.system.agent.answering.application.loop;

import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.candidate.RepositoryCandidate;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.run.AgentEvent;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.AgentRunStatus;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnswerVerificationAbandonReason;
import com.java.system.agent.answering.domain.run.PendingTerminalResponse;
import com.java.system.agent.answering.domain.run.RunAttempt;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.run.RunRequestIdentity;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.in.AnswerExecutionContractException;
import com.java.system.agent.answering.port.in.AnswerExecutionMode;
import com.java.system.agent.answering.port.out.AnalysisAttemptIdGenerator;
import com.java.system.agent.answering.port.out.AgentTransitionConflictException;
import com.java.system.agent.answering.port.out.CapabilityExecutionContractException;
import com.java.system.agent.answering.port.out.RepositoryDescriptor;
import com.java.system.agent.answering.port.out.SessionPort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 依 durable run 狀態準備 active execution 或完成 terminal reconciliation 的 application 元件
 */
final class AgentRunRecoveryCoordinator {

    private final AgentRunTransitions transitions;
    private final AgentLoopTelemetry telemetry;
    private final ContextIssuer contextIssuer;
    private final AnalysisAttemptIdGenerator attemptIdGenerator;
    private final SessionPort sessionPort;
    private final AnswerActionExecutor answerActionExecutor;
    private final TerminalResponseCoordinator terminalResponseCoordinator;

    AgentRunRecoveryCoordinator(
            AgentRunTransitions transitions,
            AgentLoopTelemetry telemetry,
            ContextIssuer contextIssuer,
            AnalysisAttemptIdGenerator attemptIdGenerator,
            SessionPort sessionPort,
            AnswerActionExecutor answerActionExecutor,
            TerminalResponseCoordinator terminalResponseCoordinator) {
        this.transitions = Objects.requireNonNull(transitions, "agent run transitions must not be null");
        this.telemetry = Objects.requireNonNull(telemetry, "agent loop telemetry must not be null");
        this.contextIssuer = Objects.requireNonNull(contextIssuer, "context issuer must not be null");
        this.attemptIdGenerator = Objects.requireNonNull(
                attemptIdGenerator, "analysis attempt ID generator must not be null");
        this.sessionPort = Objects.requireNonNull(sessionPort, "session port must not be null");
        this.answerActionExecutor = Objects.requireNonNull(
                answerActionExecutor, "answer action executor must not be null");
        this.terminalResponseCoordinator = Objects.requireNonNull(
                terminalResponseCoordinator, "terminal response coordinator must not be null");
    }

    AgentRunRecoveryOutcome recover(AgentLoopRequest request) {
        Objects.requireNonNull(request, "agent loop request must not be null");
        Optional<AgentRunState> persisted = findPersistedState(request);
        if (request.executionMode() == AnswerExecutionMode.TERMINAL_RECONCILIATION) {
            return new AgentRunRecoveryOutcome.Terminal(reconcileTerminal(request, persisted));
        }
        if (persisted.isPresent()) {
            AgentRunState persistedState = persisted.orElseThrow();
            Optional<AgentRunRecoveryOutcome> dispatched = dispatchPersistedState(request, persistedState);
            if (dispatched.isPresent()) {
                return dispatched.orElseThrow();
            }
            return switch (request.executionMode()) {
                case INITIAL -> throw new AgentRunInProgressException(
                        "agent run is already in progress: " + request.runId().value());
                case CAPACITY_RESUME -> new AgentRunRecoveryOutcome.Active(
                        prepareCapacityResumeExecution(request, persistedState));
                case RETRY -> recoverRetryExecution(request, persistedState);
                case TERMINAL_RECONCILIATION -> throw new IllegalStateException("terminal reconciliation was handled first");
            };
        }
        return switch (request.executionMode()) {
            case INITIAL, RETRY -> recoverBootstrap(request);
            case CAPACITY_RESUME -> throw new AnswerExecutionContractException(
                    "capacity resume requires a persisted nonterminal agent run");
            case TERMINAL_RECONCILIATION -> throw new IllegalStateException("terminal reconciliation was handled first");
        };
    }

    private Optional<AgentRunState> findPersistedState(AgentLoopRequest request) {
        return transitions.findByRunId(request.runId());
    }

    private AgentLoopResult reconcileTerminal(AgentLoopRequest request, Optional<AgentRunState> persisted) {
        if (persisted.isEmpty()) {
            throw new AnswerExecutionContractException("terminal reconciliation requires a persisted agent run");
        }
        AgentRunState state = persisted.orElseThrow();
        validateRequestIdentity(request, state);
        try {
            if (state.status() == AgentRunStatus.CONCLUDED) {
                return terminalResponseCoordinator.fromConcludedState(state);
            }
            if (state.status() == AgentRunStatus.RUNNING && state.pendingTerminalResponse().isPresent()) {
                return concludePersistedTerminal(state, request, state.pendingTerminalResponse().orElseThrow());
            }
            if (state.status() == AgentRunStatus.RUNNING && state.pendingAnswerVerification().isPresent()) {
                AgentRunState abandoned = transitions.abandonAnswerVerification(
                        state, AnswerVerificationAbandonReason.RETRY_EXHAUSTED);
                return terminalResponseCoordinator.conclude(
                        abandoned, RunOutcome.FAILED, TerminalResponseCoordinator.FAILED_RESPONSE,
                        Optional.empty(), Optional.empty());
            }
            if (state.status() == AgentRunStatus.RUNNING || state.status() == AgentRunStatus.RESTARTING) {
                return terminalResponseCoordinator.conclude(
                        state, RunOutcome.FAILED, TerminalResponseCoordinator.FAILED_RESPONSE,
                        Optional.empty(), Optional.empty());
            }
        } catch (AgentTransitionConflictException | AgentLoopException | AgentRunInProgressException exception) {
            throw new AnswerExecutionContractException("terminal reconciliation could not be committed", exception);
        }
        throw new AnswerExecutionContractException("terminal reconciliation requires a safely recoverable agent run");
    }

    private AgentRunRecoveryOutcome recoverBootstrap(AgentLoopRequest request) {
        int attemptSequence = request.executionAttempt();
        BootstrapPreparation preparation;
        try {
            preparation = prepareBootstrap(request, attemptSequence);
        } catch (CapabilityExecutionContractException exception) {
            throw new AnswerExecutionContractException("runtime context issuance violated its contract", exception);
        }
        return claimInitialRun(request, preparation, attemptSequence);
    }

    private BootstrapPreparation prepareBootstrap(AgentLoopRequest request, int attemptSequence) {
        AnalysisAttemptId attemptId = attemptIdGenerator.nextAttemptId(request.runId(), attemptSequence);
        AgentRunState initialState = AgentRunState.initial(
                request.runId(), attemptId, attemptSequence, request.budget(),
                new RunRequestIdentity(request.sessionId().value(), request.participant(), request.question()));
        SessionHistory sessionHistory = Objects.requireNonNull(sessionPort.read(request.sessionId()),
                "session port must return session history");
        List<CapabilityPolicy> capabilityCatalog = telemetry.loadCapabilities(initialState);
        List<RepositoryDescriptor> repositoryCatalog = telemetry.loadRepositories(initialState);
        RunAttempt initialContext = contextIssuer.issueInitial(
                request.runId(), attemptId, RevisionVector.empty(), capabilityCatalog, repositoryCatalog);
        Set<RepositoryId> catalogRepositoryIds = repositoryCatalog.stream()
                .map(RepositoryDescriptor::repositoryId)
                .collect(Collectors.toUnmodifiableSet());
        return new BootstrapPreparation(
                initialState, initialContext, sessionHistory, capabilityCatalog, repositoryCatalog, catalogRepositoryIds);
    }

    private AgentRunRecoveryOutcome claimInitialRun(
            AgentLoopRequest request,
            BootstrapPreparation preparation,
            int attemptSequence) {
        try {
            AgentRunState state = transitions.bootstrap(preparation.initialState(), preparation.initialContext());
            return new AgentRunRecoveryOutcome.Active(new ActiveAgentExecution(
                    state,
                    preparation.sessionHistory(),
                    preparation.capabilityCatalog(),
                    preparation.repositoryCatalog(),
                    preparation.catalogRepositoryIds(),
                    attemptSequence,
                    Optional.empty()));
        } catch (AgentTransitionConflictException exception) {
            AgentRunState authoritative = transitions.findByRunId(request.runId()).orElseThrow(() -> exception);
            Optional<AgentRunRecoveryOutcome> dispatched = dispatchPersistedState(request, authoritative);
            if (dispatched.isPresent()) {
                return dispatched.orElseThrow();
            }
            throw new AgentRunInProgressException("agent run is already in progress: " + request.runId().value());
        }
    }

    private AgentRunRecoveryOutcome recoverRetryExecution(AgentLoopRequest request, AgentRunState persistedState) {
        try {
            return new AgentRunRecoveryOutcome.Active(prepareRetryExecution(request, persistedState));
        } catch (ActiveIntegrationContractException exception) {
            return new AgentRunRecoveryOutcome.Terminal(
                    terminalResponseCoordinator.concludeIntegrationFailure(exception.state(), exception));
        } catch (AgentTransitionConflictException exception) {
            Optional<AgentRunRecoveryOutcome> conflict = resolveRetryConflict(request, exception);
            if (conflict.isPresent()) {
                return conflict.orElseThrow();
            }
            throw new AgentRunInProgressException("agent run is already in progress: " + request.runId().value());
        }
    }

    private ActiveAgentExecution prepareRetryExecution(AgentLoopRequest request, AgentRunState persistedState) {
        SessionHistory sessionHistory = Objects.requireNonNull(sessionPort.read(request.sessionId()),
                "session port must return session history");
        List<CapabilityPolicy> capabilityCatalog = telemetry.loadCapabilities(persistedState);
        List<RepositoryDescriptor> repositoryCatalog = telemetry.loadRepositories(persistedState);
        Set<RepositoryId> catalogRepositoryIds = repositoryCatalog.stream()
                .map(RepositoryDescriptor::repositoryId)
                .collect(Collectors.toUnmodifiableSet());
        AgentRunState restarted = restartPersistedAttempt(request, persistedState);
        RunAttempt restartedContext;
        try {
            restartedContext = contextIssuer.issueInitial(
                    request.runId(), restarted.currentAttempt().attemptId(), RevisionVector.empty(),
                    capabilityCatalog, repositoryCatalog);
        } catch (CapabilityExecutionContractException exception) {
            throw new ActiveIntegrationContractException(restarted, exception);
        }
        AgentRunState contextualized = transitions.commitContext(restarted, restartedContext);
        return new ActiveAgentExecution(
                contextualized,
                sessionHistory,
                capabilityCatalog,
                repositoryCatalog,
                catalogRepositoryIds,
                contextualized.attemptSequence(),
                Optional.empty());
    }

    private ActiveAgentExecution prepareCapacityResumeExecution(AgentLoopRequest request, AgentRunState persistedState) {
        validateRequestIdentity(request, persistedState);
        if (persistedState.status() != AgentRunStatus.RUNNING) {
            throw new AnswerExecutionContractException("capacity resume requires a persisted running agent run");
        }
        SessionHistory sessionHistory = Objects.requireNonNull(sessionPort.read(request.sessionId()),
                "session port must return session history");
        return activeExecution(persistedState, sessionHistory, Optional.empty());
    }

    private Optional<AgentRunRecoveryOutcome> resumePendingVerification(
            AgentLoopRequest request,
            AgentRunState persisted) {
        validateRequestIdentity(request, persisted);
        SessionHistory sessionHistory = Objects.requireNonNull(sessionPort.read(request.sessionId()),
                "session port must return session history");
        ActionLaneOutcome outcome = answerActionExecutor.resumePending(request, sessionHistory, persisted);
        return switch (outcome) {
            case ActionLaneOutcome.Terminal terminal -> Optional.of(new AgentRunRecoveryOutcome.Terminal(terminal.result()));
            case ActionLaneOutcome.Continue continued -> Optional.of(new AgentRunRecoveryOutcome.Active(
                    activeExecution(continued.state(), sessionHistory, continued.latestRejection())));
        };
    }

    private AgentRunState restartPersistedAttempt(AgentLoopRequest request, AgentRunState persistedState) {
        AgentRunState restarting = persistedState;
        if (persistedState.status() == AgentRunStatus.RUNNING) {
            restarting = transitions.apply(persistedState, new AgentEvent.AttemptInvalidated(
                    persistedState.runId(), persistedState.currentAttempt().attemptId(), persistedState.stateRevision(),
                    "infrastructure retry requested a fresh attempt", false));
        }
        if (restarting.status() != AgentRunStatus.RESTARTING) {
            throw new AgentRunInProgressException("agent run is already in progress: " + request.runId().value());
        }
        int nextAttemptSequence = Math.incrementExact(restarting.attemptSequence());
        AnalysisAttemptId nextAttemptId = attemptIdGenerator.nextAttemptId(request.runId(), nextAttemptSequence);
        return transitions.apply(restarting, new AgentEvent.AttemptStarted(
                restarting.runId(), restarting.currentAttempt().attemptId(), restarting.stateRevision(),
                RunAttempt.empty(nextAttemptId)));
    }

    private Optional<AgentRunRecoveryOutcome> resolveRetryConflict(
            AgentLoopRequest request,
            AgentTransitionConflictException exception) {
        AgentRunState authoritative = transitions.findByRunId(request.runId()).orElseThrow(() -> exception);
        return dispatchPersistedState(request, authoritative);
    }

    private Optional<AgentRunRecoveryOutcome> dispatchPersistedState(AgentLoopRequest request, AgentRunState persisted) {
        validateRequestIdentity(request, persisted);
        if (persisted.status() == AgentRunStatus.CONCLUDED) {
            return Optional.of(new AgentRunRecoveryOutcome.Terminal(
                    terminalResponseCoordinator.fromConcludedState(persisted)));
        }
        if (persisted.status() == AgentRunStatus.RUNNING && persisted.pendingAnswerVerification().isPresent()) {
            return resumePendingVerification(request, persisted);
        }
        if (persisted.status() == AgentRunStatus.RUNNING && persisted.pendingTerminalResponse().isPresent()) {
            PendingTerminalResponse pending = persisted.pendingTerminalResponse().orElseThrow();
            return Optional.of(new AgentRunRecoveryOutcome.Terminal(concludePersistedTerminal(persisted, request, pending)));
        }
        return Optional.empty();
    }

    private AgentLoopResult concludePersistedTerminal(
            AgentRunState persisted,
            AgentLoopRequest request,
            PendingTerminalResponse pending) {
        try {
            return terminalResponseCoordinator.concludePersistedTerminal(persisted, pending);
        } catch (AgentTransitionConflictException exception) {
            AgentRunState authoritative = transitions.findByRunId(request.runId()).orElseThrow(() -> exception);
            validateRequestIdentity(request, authoritative);
            if (authoritative.status() == AgentRunStatus.CONCLUDED) {
                return terminalResponseCoordinator.fromConcludedState(authoritative);
            }
            throw new AgentRunInProgressException("agent run is already in progress: " + request.runId().value());
        }
    }

    private void validateRequestIdentity(AgentLoopRequest request, AgentRunState state) {
        if (!state.requestIdentity().sessionIdValue().equals(request.sessionId().value())
                || !state.requestIdentity().participant().equals(request.participant())
                || !state.requestIdentity().questionText().equals(request.question())) {
            throw new IllegalArgumentException("incoming request does not match the persisted request identity");
        }
    }

    private ActiveAgentExecution activeExecution(
            AgentRunState state,
            SessionHistory sessionHistory,
            Optional<String> latestRejection) {
        List<CapabilityPolicy> capabilities = List.copyOf(state.currentAttempt().issuedCapabilities().values());
        Map<RepositoryId, RepositoryDescriptor> repositories = new LinkedHashMap<>();
        for (IssuedCandidate issued : state.currentAttempt().issuedCandidates().values()) {
            if (issued.candidate() instanceof RepositoryCandidate repository) {
                repositories.putIfAbsent(repository.repositoryId(),
                        new RepositoryDescriptor(repository.repositoryId(), repository.description()));
            }
        }
        return new ActiveAgentExecution(
                state,
                sessionHistory,
                capabilities,
                List.copyOf(repositories.values()),
                Set.copyOf(repositories.keySet()),
                state.attemptSequence(),
                latestRejection);
    }

    private record BootstrapPreparation(
            AgentRunState initialState,
            RunAttempt initialContext,
            SessionHistory sessionHistory,
            List<CapabilityPolicy> capabilityCatalog,
            List<RepositoryDescriptor> repositoryCatalog,
            Set<RepositoryId> catalogRepositoryIds) {
    }

    /**
     * 在已持久化 attempt 中保留可安全終結 state 的整合契約失敗
     */
    private static final class ActiveIntegrationContractException extends IllegalStateException {

        private final AgentRunState state;

        private ActiveIntegrationContractException(AgentRunState state, RuntimeException cause) {
            super("active runtime integration contract failure", cause);
            this.state = Objects.requireNonNull(state, "active agent state must not be null");
        }

        private AgentRunState state() {
            return state;
        }
    }
}
