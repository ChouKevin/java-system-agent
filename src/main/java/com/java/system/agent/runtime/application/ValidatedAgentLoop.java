package com.java.system.agent.runtime.application;

import com.java.system.agent.runtime.application.state.AgentTransitionCommitException;
import com.java.system.agent.runtime.application.state.AgentTransitionCommitter;
import com.java.system.agent.runtime.application.validation.ActionValidation;
import com.java.system.agent.runtime.application.validation.AgentActionValidator;
import com.java.system.agent.runtime.application.validation.AgentValidationContext;
import com.java.system.agent.runtime.application.validation.AnswerDocumentValidation;
import com.java.system.agent.runtime.application.validation.AnswerDocumentValidator;
import com.java.system.agent.runtime.application.validation.AnswerVerdictValidator;
import com.java.system.agent.runtime.domain.action.AgentAction;
import com.java.system.agent.runtime.domain.action.AnswerAction;
import com.java.system.agent.runtime.domain.action.ClarifyAction;
import com.java.system.agent.runtime.domain.action.QueryAction;
import com.java.system.agent.runtime.domain.answer.AnswerDisposition;
import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.answer.AnswerStatement;
import com.java.system.agent.runtime.domain.answer.AnswerVerdict;
import com.java.system.agent.runtime.domain.answer.AnswerAcceptance;
import com.java.system.agent.runtime.domain.answer.AnswerVerificationBasis;
import com.java.system.agent.runtime.domain.answer.AnswerVerificationMode;
import com.java.system.agent.runtime.domain.answer.StatementId;
import com.java.system.agent.runtime.domain.answer.StatementVerdict;
import com.java.system.agent.runtime.domain.answer.StatementVerdictStatus;
import com.java.system.agent.runtime.domain.candidate.IssuedCandidate;
import com.java.system.agent.runtime.domain.candidate.RepositoryCandidate;
import com.java.system.agent.runtime.domain.conversation.ConversationTurn;
import com.java.system.agent.runtime.domain.conversation.SessionHistory;
import com.java.system.agent.runtime.domain.conversation.ConversationTurnType;
import com.java.system.agent.runtime.domain.evidence.IssuedEvidence;
import com.java.system.agent.runtime.domain.handle.HandleBinding;
import com.java.system.agent.runtime.domain.handle.CandidateHandle;
import com.java.system.agent.runtime.domain.handle.EvidenceHandle;
import com.java.system.agent.runtime.domain.observation.AgentObservation;
import com.java.system.agent.runtime.domain.observation.ObservationCode;
import com.java.system.agent.runtime.domain.observation.ObservationId;
import com.java.system.agent.runtime.domain.observation.ObservationSource;
import com.java.system.agent.runtime.domain.run.AgentEvent;
import com.java.system.agent.runtime.domain.run.AgentRunState;
import com.java.system.agent.runtime.domain.run.AgentRunStatus;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.run.RunAttempt;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.run.RunResponseKind;
import com.java.system.agent.runtime.domain.run.PendingTerminalResponse;
import com.java.system.agent.runtime.domain.run.PendingAnswerVerification;
import com.java.system.agent.runtime.domain.run.AnswerVerificationAbandonReason;
import com.java.system.agent.runtime.domain.run.RunRequestIdentity;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import com.java.system.agent.runtime.domain.capability.CapabilityDescriptor;
import com.java.system.agent.runtime.port.out.AgentActionPort;
import com.java.system.agent.runtime.port.out.AgentActionProposal;
import com.java.system.agent.runtime.port.out.AgentPromptContext;
import com.java.system.agent.runtime.port.out.CapabilityExecutionPort;
import com.java.system.agent.runtime.port.out.CapabilityExecutionContractException;
import com.java.system.agent.runtime.port.out.CapabilityExecutionResult;
import com.java.system.agent.runtime.port.out.CapabilityInvocation;
import com.java.system.agent.runtime.port.out.AnalysisAttemptIdGenerator;
import com.java.system.agent.runtime.port.out.AnalysisCancellationPort;
import com.java.system.agent.runtime.port.out.AnswerVerificationContext;
import com.java.system.agent.runtime.port.out.AnswerVerificationPort;
import com.java.system.agent.runtime.port.out.AnswerVerificationResult;
import com.java.system.agent.runtime.port.out.AnswerVerificationUnavailableException;
import com.java.system.agent.runtime.port.out.AnswerVerificationContractException;
import com.java.system.agent.runtime.port.in.AnswerExecutionUnavailableException;
import com.java.system.agent.runtime.port.in.AnswerExecutionContractException;
import com.java.system.agent.runtime.port.out.CapabilityCatalogPort;
import com.java.system.agent.runtime.port.out.SessionPort;
import com.java.system.agent.runtime.port.out.RepositoryCatalogPort;
import com.java.system.agent.runtime.port.out.RepositoryDescriptor;
import com.java.system.agent.runtime.port.out.RepositoryRevisionPort;
import com.java.system.agent.runtime.port.out.RepositoryRevisionResult;
import com.java.system.agent.runtime.port.out.RepositoryRevisionFailure;
import com.java.system.agent.runtime.port.out.RepositoryRevisionContractException;
import com.java.system.agent.runtime.port.out.AgentTransitionConflictException;
import com.java.system.agent.runtime.port.out.TerminalAcceptanceCancelledException;
import com.java.system.agent.runtime.port.in.AnswerExecutionMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * 唯一推進 bounded action、capability execution、verification 與 terminal persistence 的 orchestrator
 */
public final class ValidatedAgentLoop {

    public static final String INCONCLUSIVE_RESPONSE = "目前資訊不足以產生可驗證的回答";
    public static final String FAILED_RESPONSE = "分析流程發生錯誤，未回傳未驗證內容";
    public static final String CANCELLED_RESPONSE = "分析已取消";
    private static final Logger LOGGER = Logger.getLogger(ValidatedAgentLoop.class.getName());

    private final AgentActionPort actionPort;
    private final CapabilityExecutionPort capabilityExecutionPort;
    private final AnswerVerificationPort verificationPort;
    private final AnswerVerificationMode answerVerificationMode;
    private final SessionPort sessionPort;
    private final RepositoryCatalogPort repositoryCatalogPort;
    private final CapabilityCatalogPort capabilityCatalogPort;
    private final RepositoryRevisionPort repositoryRevisionPort;
    private final AnalysisCancellationPort cancellationPort;
    private final AnalysisAttemptIdGenerator attemptIdGenerator;
    private final AgentActionValidator actionValidator;
    private final AnswerDocumentValidator documentValidator;
    private final AnswerVerdictValidator verdictValidator;
    private final AgentTransitionCommitter transitionCommitter;
    private final ContextIssuer contextIssuer;

    public ValidatedAgentLoop(
            AgentActionPort actionPort,
            CapabilityExecutionPort capabilityExecutionPort,
            AnswerVerificationPort verificationPort,
            AnswerVerificationMode answerVerificationMode,
            SessionPort sessionPort,
            RepositoryCatalogPort repositoryCatalogPort,
            CapabilityCatalogPort capabilityCatalogPort,
            RepositoryRevisionPort repositoryRevisionPort,
            AnalysisCancellationPort cancellationPort,
            AnalysisAttemptIdGenerator attemptIdGenerator,
            AgentActionValidator actionValidator,
            AnswerDocumentValidator documentValidator,
            AnswerVerdictValidator verdictValidator,
            AgentTransitionCommitter transitionCommitter,
            ContextIssuer contextIssuer) {
        this.actionPort = Objects.requireNonNull(actionPort, "agent action port must not be null");
        this.capabilityExecutionPort = Objects.requireNonNull(
                capabilityExecutionPort, "capability execution port must not be null");
        this.verificationPort = Objects.requireNonNull(
                verificationPort, "answer verification port must not be null");
        this.answerVerificationMode = Objects.requireNonNull(
                answerVerificationMode, "answer verification mode must not be null");
        this.sessionPort = Objects.requireNonNull(sessionPort, "session port must not be null");
        this.repositoryCatalogPort = Objects.requireNonNull(
                repositoryCatalogPort, "repository catalog port must not be null");
        this.capabilityCatalogPort = Objects.requireNonNull(
                capabilityCatalogPort, "capability catalog port must not be null");
        this.repositoryRevisionPort = Objects.requireNonNull(
                repositoryRevisionPort, "repository revision port must not be null");
        this.cancellationPort = Objects.requireNonNull(
                cancellationPort, "analysis cancellation port must not be null");
        this.attemptIdGenerator = Objects.requireNonNull(
                attemptIdGenerator, "analysis attempt ID generator must not be null");
        this.actionValidator = Objects.requireNonNull(actionValidator, "agent action validator must not be null");
        this.documentValidator = Objects.requireNonNull(
                documentValidator, "answer document validator must not be null");
        this.verdictValidator = Objects.requireNonNull(
                verdictValidator, "answer verdict validator must not be null");
        this.transitionCommitter = Objects.requireNonNull(
                transitionCommitter, "agent transition committer must not be null");
        this.contextIssuer = Objects.requireNonNull(contextIssuer, "context issuer must not be null");
    }

    public AgentLoopResult execute(AgentLoopRequest request) {
        Objects.requireNonNull(request, "agent loop request must not be null");
        Optional<AgentRunState> persisted = findPersistedState(request);
        if (request.executionMode() == AnswerExecutionMode.TERMINAL_RECONCILIATION) {
            return reconcileTerminal(request, persisted);
        }
        ActiveExecution execution;
        if (persisted.isPresent()) {
            AgentRunState persistedState = persisted.orElseThrow();
            PersistedDispatch dispatch = dispatchPersistedState(request, persistedState);
            if (dispatch.result().isPresent()) {
                return dispatch.result().orElseThrow();
            }
            if (dispatch.execution().isPresent()) {
                execution = dispatch.execution().orElseThrow();
            } else {
                if (request.executionMode() == AnswerExecutionMode.INITIAL) {
                    throw new AgentRunInProgressException("agent run is already in progress: " + request.runId().value());
                }
                try {
                    execution = prepareRetryExecution(request, persistedState);
                } catch (ActiveIntegrationContractException exception) {
                    return concludeIntegrationFailure(exception.state(), request, exception);
                } catch (AgentTransitionConflictException exception) {
                    PersistedDispatch conflict = resolveRetryConflict(request, exception);
                    if (conflict.result().isPresent()) {
                        return conflict.result().orElseThrow();
                    }
                    if (conflict.execution().isPresent()) {
                        execution = conflict.execution().orElseThrow();
                    } else {
                        throw new AgentRunInProgressException(
                                "agent run is already in progress: " + request.runId().value());
                    }
                }
            }
        } else {
            int attemptSequence = request.executionAttempt();
            BootstrapPreparation preparation;
            try {
                preparation = prepareBootstrap(request, attemptSequence);
            } catch (CapabilityExecutionContractException exception) {
                throw new AnswerExecutionContractException("runtime context issuance violated its contract", exception);
            }
            InitialClaim initialClaim = claimInitialRun(request, preparation.initialState(), preparation.initialContext());
            if (initialClaim.result().isPresent()) {
                return initialClaim.result().orElseThrow();
            }
            execution = initialClaim.execution().orElseGet(() -> new ActiveExecution(
                    initialClaim.state().orElseThrow(),
                    preparation.sessionHistory(),
                    preparation.capabilityCatalog(),
                    preparation.repositoryCatalog(),
                    preparation.catalogRepositoryIds(),
                    attemptSequence,
                    Optional.empty(),
                    false));
        }
        AgentRunState state = execution.state();
        SessionHistory sessionHistory = execution.sessionHistory();
        List<CapabilityDescriptor> capabilityCatalog = execution.capabilityCatalog();
        List<RepositoryDescriptor> repositoryCatalog = execution.repositoryCatalog();
        Set<RepositoryId> catalogRepositoryIds = execution.catalogRepositoryIds();
        int attemptSequence = execution.attemptSequence();

        Optional<String> latestRejection = execution.latestRejection();
        boolean forcedFinalResponse = execution.forcedFinalResponse();
        while (true) {
            if (cancellationPort.isCancellationRequested(request.runId())) {
                return conclude(state, request, RunOutcome.CANCELLED, CANCELLED_RESPONSE, Optional.empty());
            }
            boolean finalResponseMode = forcedFinalResponse || normalBudgetExhausted(state.budget());
            AgentActionProposal proposal = Objects.requireNonNull(
                    actionPort.nextAction(prompt(request, sessionHistory, state, latestRejection, finalResponseMode)),
                    "agent action port must return a proposal");
            if (cancellationPort.isCancellationRequested(request.runId())) {
                return conclude(state, request, RunOutcome.CANCELLED, CANCELLED_RESPONSE, Optional.empty());
            }
            if (proposal instanceof AgentActionProposal.Malformed malformed) {
                state = reject(state, Optional.empty(), malformed.description(), finalResponseMode);
                state = recordRuntimeObservation(state, ObservationCode.ACTION_REJECTED, malformed.description(),
                        Set.of(), Set.of(), "agent-action-parser");
                if (finalResponseMode) {
                    return conclude(state, request, RunOutcome.INCONCLUSIVE,
                            INCONCLUSIVE_RESPONSE, Optional.empty());
                }
                latestRejection = Optional.of(malformed.description());
                continue;
            }

            AgentAction action = ((AgentActionProposal.Proposed) proposal).action();
            ActionValidation validation = actionValidator.validate(action, validationContext(state, finalResponseMode));
            if (validation instanceof ActionValidation.Rejected rejected) {
                state = reject(state, Optional.of(action), rejected.description(), finalResponseMode);
                state = recordRuntimeObservation(state, ObservationCode.ACTION_REJECTED, rejected.description(),
                        Set.of(), Set.of(), "agent-action-validator");
                if (finalResponseMode) {
                    return conclude(state, request, RunOutcome.INCONCLUSIVE,
                            INCONCLUSIVE_RESPONSE, Optional.empty());
                }
                latestRejection = Optional.of(rejected.description());
                continue;
            }
            if (action instanceof QueryAction queryAction) {
                QueryExecution queryExecution = executeQuery(
                        request,
                        state,
                        queryAction,
                        attemptSequence,
                        capabilityCatalog,
                        repositoryCatalog,
                        catalogRepositoryIds);
                if (queryExecution.result().isPresent()) {
                    return queryExecution.result().orElseThrow();
                }
                state = queryExecution.state();
                attemptSequence = queryExecution.attemptSequence();
                forcedFinalResponse = queryExecution.forceFinalResponse();
                latestRejection = queryExecution.latestRejection();
                continue;
            }
            if (action instanceof AnswerAction answerAction) {
                TerminalExecution terminal = executeAnswer(
                        request, sessionHistory, state, answerAction, finalResponseMode);
                if (terminal.result().isPresent()) {
                    return terminal.result().orElseThrow();
                }
                state = terminal.state();
                latestRejection = terminal.latestRejection();
                forcedFinalResponse = terminal.forceFinalResponse();
                continue;
            }
            ClarifyAction clarification = (ClarifyAction) action;
            return acceptClarification(request, state, clarification, finalResponseMode);
        }
    }

    private Optional<AgentRunState> findPersistedState(AgentLoopRequest request) {
        return transitionCommitter.findByRunId(request.runId());
    }

    private AgentLoopResult reconcileTerminal(AgentLoopRequest request, Optional<AgentRunState> persisted) {
        if (persisted.isEmpty()) {
            throw new AnswerExecutionContractException(
                    "terminal reconciliation requires a persisted agent run");
        }
        AgentRunState state = persisted.orElseThrow();
        validateRequestIdentity(request, state);
        try {
            if (state.status() == AgentRunStatus.CONCLUDED) {
                return concludedResult(state);
            }
            if (state.status() == AgentRunStatus.RUNNING && state.pendingTerminalResponse().isPresent()) {
                PendingTerminalResponse pending = state.pendingTerminalResponse().orElseThrow();
                sessionPort.append(pending.sessionId(), pending.turn());
                return concludePersistedTerminal(state, request, pending);
            }
            if (state.status() == AgentRunStatus.RUNNING && state.pendingAnswerVerification().isPresent()) {
                AgentRunState abandoned = abandonAnswerVerification(
                        state, AnswerVerificationAbandonReason.RETRY_EXHAUSTED);
                return conclude(abandoned, request, RunOutcome.FAILED, FAILED_RESPONSE, Optional.empty());
            }
            if (state.status() == AgentRunStatus.RUNNING || state.status() == AgentRunStatus.RESTARTING) {
                return conclude(state, request, RunOutcome.FAILED, FAILED_RESPONSE, Optional.empty());
            }
        } catch (AgentTransitionConflictException | AgentLoopException | AgentRunInProgressException exception) {
            throw new AnswerExecutionContractException("terminal reconciliation could not be committed", exception);
        }
        throw new AnswerExecutionContractException("terminal reconciliation requires a safely recoverable agent run");
    }

    private BootstrapPreparation prepareBootstrap(AgentLoopRequest request, int attemptSequence) {
        AnalysisAttemptId attemptId = attemptIdGenerator.nextAttemptId(request.runId(), attemptSequence);
        AgentRunState initialState = AgentRunState.initial(
                request.runId(),
                attemptId,
                attemptSequence,
                request.budget(),
                new RunRequestIdentity(request.sessionId().value(), request.question()));
        SessionHistory sessionHistory = Objects.requireNonNull(sessionPort.read(request.sessionId()),
                "session port must return session history");
        List<CapabilityDescriptor> capabilityCatalog = List.copyOf(Objects.requireNonNull(
                capabilityCatalogPort.availableCapabilities(),
                "capability catalog port must return a catalog"));
        List<RepositoryDescriptor> repositoryCatalog = List.copyOf(Objects.requireNonNull(
                repositoryCatalogPort.availableRepositories(),
                "repository catalog port must return a catalog"));
        RunAttempt initialContext = contextIssuer.issueInitial(
                request.runId(), attemptId, RevisionVector.empty(), capabilityCatalog, repositoryCatalog);
        Set<RepositoryId> catalogRepositoryIds = repositoryCatalog.stream()
                .map(RepositoryDescriptor::repositoryId)
                .collect(Collectors.toUnmodifiableSet());
        return new BootstrapPreparation(
                initialState, initialContext, sessionHistory, capabilityCatalog, repositoryCatalog, catalogRepositoryIds);
    }

    private InitialClaim claimInitialRun(
            AgentLoopRequest request,
            AgentRunState initialState,
            RunAttempt initialContext) {
        try {
            return new InitialClaim(Optional.of(bootstrap(initialState, initialContext)), Optional.empty(), Optional.empty());
        } catch (AgentTransitionConflictException exception) {
            AgentRunState authoritative = transitionCommitter.findByRunId(request.runId())
                    .orElseThrow(() -> exception);
            PersistedDispatch dispatch = dispatchPersistedState(request, authoritative);
            if (dispatch.execution().isEmpty() && dispatch.result().isEmpty()) {
                throw new AgentRunInProgressException("agent run is already in progress: " + request.runId().value());
            }
            return new InitialClaim(Optional.empty(), dispatch.execution(), dispatch.result());
        }
    }

    private ActiveExecution prepareRetryExecution(AgentLoopRequest request, AgentRunState persistedState) {
        SessionHistory sessionHistory = Objects.requireNonNull(sessionPort.read(request.sessionId()),
                "session port must return session history");
        List<CapabilityDescriptor> capabilityCatalog = List.copyOf(Objects.requireNonNull(
                capabilityCatalogPort.availableCapabilities(),
                "capability catalog port must return a catalog"));
        List<RepositoryDescriptor> repositoryCatalog = List.copyOf(Objects.requireNonNull(
                repositoryCatalogPort.availableRepositories(),
                "repository catalog port must return a catalog"));
        Set<RepositoryId> catalogRepositoryIds = repositoryCatalog.stream()
                .map(RepositoryDescriptor::repositoryId)
                .collect(Collectors.toUnmodifiableSet());
        AgentRunState restarted = restartPersistedAttempt(request, persistedState);
        RunAttempt restartedContext;
        try {
            restartedContext = contextIssuer.issueInitial(
                    request.runId(),
                    restarted.currentAttempt().attemptId(),
                    RevisionVector.empty(),
                    capabilityCatalog,
                    repositoryCatalog);
        } catch (CapabilityExecutionContractException exception) {
            throw new ActiveIntegrationContractException(restarted, exception);
        }
        AgentRunState contextualized = commitContext(restarted, restartedContext);
        return new ActiveExecution(
                contextualized,
                sessionHistory,
                capabilityCatalog,
                repositoryCatalog,
                catalogRepositoryIds,
                contextualized.attemptSequence(),
                Optional.empty(),
                false);
    }

    private PendingVerificationResume resumePendingVerification(AgentLoopRequest request, AgentRunState persisted) {
        validateRequestIdentity(request, persisted);
        SessionHistory sessionHistory = Objects.requireNonNull(sessionPort.read(request.sessionId()),
                "session port must return session history");
        TerminalExecution terminal = verifyPendingAnswer(request, sessionHistory, persisted);
        if (terminal.result().isPresent()) {
            return new PendingVerificationResume(Optional.empty(), terminal.result());
        }
        List<CapabilityDescriptor> capabilities = List.copyOf(persisted.currentAttempt().issuedCapabilities().values());
        Map<RepositoryId, RepositoryDescriptor> repositories = new LinkedHashMap<>();
        for (IssuedCandidate issued : persisted.currentAttempt().issuedCandidates().values()) {
            if (issued.candidate() instanceof RepositoryCandidate repository) {
                repositories.putIfAbsent(repository.repositoryId(),
                        new RepositoryDescriptor(repository.repositoryId(), repository.description()));
            }
        }
        return new PendingVerificationResume(Optional.of(new ActiveExecution(
                terminal.state(),
                sessionHistory,
                capabilities,
                List.copyOf(repositories.values()),
                Set.copyOf(repositories.keySet()),
                terminal.state().attemptSequence(),
                terminal.latestRejection(),
                terminal.forceFinalResponse())), Optional.empty());
    }

    private AgentRunState restartPersistedAttempt(AgentLoopRequest request, AgentRunState persistedState) {
        AgentRunState restarting = persistedState;
        if (persistedState.status() == AgentRunStatus.RUNNING) {
            restarting = commit(persistedState, new AgentEvent.AttemptInvalidated(
                    persistedState.runId(),
                    persistedState.currentAttempt().attemptId(),
                    persistedState.stateRevision(),
                    "infrastructure retry requested a fresh attempt",
                    false));
        }
        if (restarting.status() != AgentRunStatus.RESTARTING) {
            throw new AgentRunInProgressException("agent run is already in progress: " + request.runId().value());
        }
        int nextAttemptSequence = Math.incrementExact(restarting.attemptSequence());
        AnalysisAttemptId nextAttemptId = attemptIdGenerator.nextAttemptId(request.runId(), nextAttemptSequence);
        return commit(restarting, new AgentEvent.AttemptStarted(
                restarting.runId(),
                restarting.currentAttempt().attemptId(),
                restarting.stateRevision(),
                RunAttempt.empty(nextAttemptId)));
    }

    private PersistedDispatch resolveRetryConflict(AgentLoopRequest request, AgentTransitionConflictException exception) {
        AgentRunState authoritative = transitionCommitter.findByRunId(request.runId())
                .orElseThrow(() -> exception);
        return dispatchPersistedState(request, authoritative);
    }

    private PersistedDispatch dispatchPersistedState(AgentLoopRequest request, AgentRunState persisted) {
        validateRequestIdentity(request, persisted);
        if (persisted.status() == AgentRunStatus.CONCLUDED) {
            return new PersistedDispatch(Optional.empty(), Optional.of(concludedResult(persisted)));
        }
        if (persisted.status() == AgentRunStatus.RUNNING && persisted.pendingAnswerVerification().isPresent()) {
            PendingVerificationResume resumed = resumePendingVerification(request, persisted);
            return new PersistedDispatch(resumed.execution(), resumed.result());
        }
        if (persisted.status() == AgentRunStatus.RUNNING && persisted.pendingTerminalResponse().isPresent()) {
            PendingTerminalResponse pending = persisted.pendingTerminalResponse().orElseThrow();
            sessionPort.append(pending.sessionId(), pending.turn());
            return new PersistedDispatch(Optional.empty(), Optional.of(concludePersistedTerminal(persisted, request, pending)));
        }
        return new PersistedDispatch(Optional.empty(), Optional.empty());
    }

    private AgentLoopResult concludePersistedTerminal(
            AgentRunState persisted,
            AgentLoopRequest request,
            PendingTerminalResponse pending) {
        try {
            return conclude(
                    persisted,
                    request,
                    pending.expectedOutcome(),
                    pending.turn().assistantMessage(),
                    pendingAnswerDocument(pending));
        } catch (AgentTransitionConflictException exception) {
            AgentRunState authoritative = transitionCommitter.findByRunId(request.runId())
                    .orElseThrow(() -> exception);
            validateRequestIdentity(request, authoritative);
            if (authoritative.status() == AgentRunStatus.CONCLUDED) {
                return concludedResult(authoritative);
            }
            throw new AgentRunInProgressException("agent run is already in progress: " + request.runId().value());
        }
    }

    private void validateRequestIdentity(AgentLoopRequest request, AgentRunState state) {
        if (!state.requestIdentity().sessionIdValue().equals(request.sessionId().value())
                || !state.requestIdentity().exactQuestion().equals(request.question())) {
            throw new IllegalArgumentException("incoming request does not match the persisted request identity");
        }
    }

    private AgentLoopResult concludedResult(AgentRunState state) {
        RunOutcome outcome = state.finalOutcome().orElseThrow();
        if (state.pendingTerminalResponse().isPresent()) {
            PendingTerminalResponse pending = state.pendingTerminalResponse().orElseThrow();
            return loopResult(state, pending.turn().assistantMessage(), pendingAnswerDocument(pending));
        }
        return loopResult(state, runtimeFixedResponse(outcome), Optional.empty());
    }

    private Optional<AnswerDocument> pendingAnswerDocument(PendingTerminalResponse pending) {
        if (pending instanceof PendingTerminalResponse.Answer answer) {
            return Optional.of(answer.document());
        }
        return Optional.empty();
    }

    private String runtimeFixedResponse(RunOutcome outcome) {
        return switch (outcome) {
            case COMPLETED -> throw new IllegalStateException("completed agent run must retain its accepted answer");
            case INCONCLUSIVE -> INCONCLUSIVE_RESPONSE;
            case FAILED -> FAILED_RESPONSE;
            case CANCELLED -> CANCELLED_RESPONSE;
        };
    }

    private QueryExecution executeQuery(
            AgentLoopRequest request,
            AgentRunState currentState,
            QueryAction action,
            int attemptSequence,
            List<CapabilityDescriptor> capabilityCatalog,
            List<RepositoryDescriptor> repositoryCatalog,
            Set<RepositoryId> catalogRepositoryIds) {
        AgentRunState state = currentState;
        List<String> selectedHandleValues = action.candidates().stream()
                .map(CandidateHandle::value)
                .toList();
        List<IssuedCandidate> selectedValues = action.candidates().stream()
                .map(handle -> currentState.currentAttempt().issuedCandidates().get(handle))
                .toList();
        RevisionResolution revisionResolution;
        try {
            revisionResolution = resolveRevisions(state.currentAttempt().revisionVector(), selectedValues);
        } catch (RepositoryRevisionContractException exception) {
            return integrationContractFailure(request, state, attemptSequence, exception);
        }
        if (revisionResolution.drifted()) {
            boolean restartAllowed = state.budget().hasRevisionRestartRemaining();
            state = commit(state, new AgentEvent.AttemptInvalidated(
                    state.runId(),
                    state.currentAttempt().attemptId(),
                    state.stateRevision(),
                    "selected repository revision changed",
                    restartAllowed));
            int nextAttemptSequence = Math.incrementExact(state.attemptSequence());
            AnalysisAttemptId nextAttemptId = attemptIdGenerator.nextAttemptId(state.runId(), nextAttemptSequence);
            RunAttempt restartedContext;
            try {
                restartedContext = contextIssuer.issueInitial(
                        state.runId(),
                        nextAttemptId,
                        RevisionVector.empty(),
                        capabilityCatalog,
                        repositoryCatalog);
            } catch (CapabilityExecutionContractException exception) {
                return integrationContractFailure(request, state, nextAttemptSequence, exception);
            }
            AnalysisAttemptId invalidatedAttemptId = state.currentAttempt().attemptId();
            state = commit(state, new AgentEvent.AttemptStarted(
                    state.runId(),
                    invalidatedAttemptId,
                    state.stateRevision(),
                    RunAttempt.empty(nextAttemptId)));
            state = commitContext(state, restartedContext);
            if (!restartAllowed) {
                String description = "repository revision changed and stale context was discarded";
                state = recordRuntimeObservation(
                        state,
                        ObservationCode.CONFLICTING_EVIDENCE,
                        description,
                        Set.of(),
                        Set.of(),
                        "repository-revision-validator");
                return new QueryExecution(
                        state,
                        nextAttemptSequence,
                        true,
                        Optional.of(description + "; restart budget is exhausted"),
                        Optional.empty());
            }
            return new QueryExecution(
                    state,
                    nextAttemptSequence,
                    false,
                    Optional.of("repository revision changed; a new attempt was started"),
                    Optional.empty());
        }
        if (revisionResolution.failure().isPresent()) {
            RepositoryRevisionFailure failure = revisionResolution.failure().orElseThrow();
            state = commit(state, new AgentEvent.ActionAccepted(
                    state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), action));
            state = commit(state, new AgentEvent.QueryBudgetConsumed(
                    state.runId(), state.currentAttempt().attemptId(), state.stateRevision()));
            state = recordRuntimeObservation(state, ObservationCode.BLOCKING_UNCERTAINTY,
                    failure.description(), Set.of(), Set.of(), failure.operationSource());
            return new QueryExecution(state, attemptSequence, false, Optional.of(failure.description()), Optional.empty());
        }
        state = commit(state, new AgentEvent.ActionAccepted(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), action));
        state = commit(state, new AgentEvent.QueryBudgetConsumed(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision()));
        if (!revisionResolution.revisions().equals(state.currentAttempt().revisionVector())) {
            RunAttempt rebound;
            try {
                rebound = contextIssuer.reissue(
                        state.runId(), state.currentAttempt(), revisionResolution.revisions());
            } catch (CapabilityExecutionContractException exception) {
                return integrationContractFailure(request, state, attemptSequence, exception);
            }
            state = commitContext(state, rebound);
        }
        if (cancellationPort.isCancellationRequested(request.runId())) {
            AgentLoopResult result = conclude(
                    state, request, RunOutcome.CANCELLED, CANCELLED_RESPONSE, Optional.empty());
            return new QueryExecution(state, attemptSequence, false, Optional.empty(), Optional.of(result));
        }
        RunAttempt queryContext = state.currentAttempt();
        List<IssuedCandidate> reboundSelection = selectedHandleValues.stream()
                .map(handleValue -> findIssuedCandidate(queryContext, handleValue))
                .toList();
        CapabilityInvocation capabilityInvocation = new CapabilityInvocation(
                findCapability(queryContext, action),
                reboundSelection,
                action.questionToResolve(),
                action.arguments(),
                queryContext.revisionVector());
        CapabilityExecutionResult result;
        try {
            result = Objects.requireNonNull(
                    capabilityExecutionPort.execute(capabilityInvocation),
                    "capability execution port must return a result");
        } catch (CapabilityExecutionContractException exception) {
            return integrationContractFailure(request, state, attemptSequence, exception);
        }
        if (result instanceof CapabilityExecutionResult.Failed failed) {
            state = recordCapabilityFailureObservation(state, ObservationCode.BLOCKING_UNCERTAINTY,
                    failed.failure().description(), Set.of(), Set.of(), failed.failure().operationSource());
            return new QueryExecution(state, attemptSequence, false, Optional.empty(), Optional.empty());
        }
        ContextIssuer.CapabilityIssue issued;
        try {
            issued = contextIssuer.issueCapabilityResult(
                    state.runId(), state.currentAttempt(), (CapabilityExecutionResult.Succeeded) result,
                    catalogRepositoryIds);
        } catch (CapabilityExecutionContractException exception) {
            return integrationContractFailure(request, state, attemptSequence, exception);
        }
        state = commitContext(state, issued.context());
        for (AgentObservation observation : issued.observations()) {
            state = commit(state, new AgentEvent.ObservationRecorded(
                    state.runId(),
                    state.currentAttempt().attemptId(),
                    state.stateRevision(),
                    observation));
        }
        return new QueryExecution(state, attemptSequence, false, Optional.empty(), Optional.empty());
    }

    private QueryExecution integrationContractFailure(
            AgentLoopRequest request,
            AgentRunState state,
            int attemptSequence,
            RuntimeException exception) {
        AgentLoopResult result = concludeIntegrationFailure(state, request, exception);
        return new QueryExecution(state, attemptSequence, false, Optional.empty(), Optional.of(result));
    }

    private TerminalExecution executeAnswer(
            AgentLoopRequest request,
            SessionHistory sessionHistory,
            AgentRunState currentState,
            AnswerAction action,
            boolean finalResponseMode) {
        AgentRunState state = currentState;
        if (cancellationPort.isCancellationRequested(request.runId())) {
            AgentLoopResult result = conclude(
                    state, request, RunOutcome.CANCELLED, CANCELLED_RESPONSE, Optional.empty());
            return new TerminalExecution(state, Optional.empty(), false, Optional.of(result));
        }
        HandleBinding binding = currentBinding(state);
        AnswerDocumentValidation documentValidation = documentValidator.validate(
                action.document(),
                state.currentAttempt().issuedEvidence(),
                state.currentAttempt().observations(),
                binding);
        PendingAnswerVerification pendingVerification = new PendingAnswerVerification(
                state.currentAttempt().attemptId(), state.currentAttempt().revisionVector(), action.document(),
                finalResponseMode, answerVerificationMode);
        state = commit(state, new AgentEvent.AnswerProposed(state.runId(), state.currentAttempt().attemptId(),
                state.stateRevision(), pendingVerification));
        return verifyPendingAnswer(request, sessionHistory, state);
    }

    private TerminalExecution verifyPendingAnswer(
            AgentLoopRequest request,
            SessionHistory sessionHistory,
            AgentRunState currentState) {
        PendingAnswerVerification pending = currentState.pendingAnswerVerification().orElseThrow(
                () -> new IllegalStateException("answer verification checkpoint is required"));
        AgentRunState state = currentState;
        if (cancellationPort.isCancellationRequested(request.runId())) {
            state = abandonAnswerVerification(state, AnswerVerificationAbandonReason.CANCELLED);
            AgentLoopResult result = conclude(
                    state, request, RunOutcome.CANCELLED, CANCELLED_RESPONSE, Optional.empty());
            return new TerminalExecution(state, Optional.empty(), false, Optional.of(result));
        }
        HandleBinding binding = currentBinding(state);
        AnswerDocumentValidation documentValidation = documentValidator.validate(
                pending.document(),
                state.currentAttempt().issuedEvidence(),
                state.currentAttempt().observations(),
                binding);
        AnswerVerificationContext verificationContext = new AnswerVerificationContext(
                request.question(),
                sessionHistory,
                pending.document(),
                List.copyOf(state.currentAttempt().issuedEvidence().values()),
                List.copyOf(state.currentAttempt().observations().values()),
                List.copyOf(documentValidation.citedEvidence().values()),
                List.copyOf(documentValidation.referencedObservations().values()));
        AnswerVerificationResult verificationResult;
        long verificationStartedNanos = System.nanoTime();
        String verificationResultCategory = "CONTRACT_EXCEPTION";
        try {
            verificationResult = Objects.requireNonNull(
                    verificationPort.verify(pending.verificationMode(), verificationContext),
                    "answer verification port must return a result");
            verificationResultCategory = verificationResultCategory(verificationResult);
        } catch (AnswerVerificationUnavailableException exception) {
            verificationResultCategory = "VERIFIER_UNAVAILABLE";
            throw new AnswerExecutionUnavailableException("answer verification is unavailable", exception);
        } catch (AnswerVerificationContractException exception) {
            return integrationContractTerminalFailure(state, request, exception);
        } finally {
            logVerificationOperation(state, pending.verificationMode(), verificationResultCategory, verificationStartedNanos);
        }
        if (cancellationPort.isCancellationRequested(request.runId())) {
            state = abandonAnswerVerification(state, AnswerVerificationAbandonReason.CANCELLED);
            AgentLoopResult result = conclude(
                    state, request, RunOutcome.CANCELLED, CANCELLED_RESPONSE, Optional.empty());
            return new TerminalExecution(state, Optional.empty(), false, Optional.of(result));
        }
        AnswerAcceptance acceptance;
        if (pending.verificationMode() == AnswerVerificationMode.LLM
                && verificationResult instanceof AnswerVerificationResult.LlmVerdict llmVerdict) {
            AnswerVerdict verdict = llmVerdict.verdict();
            try {
                verdictValidator.validate(documentValidation, verdict);
            } catch (IllegalArgumentException exception) {
                return integrationContractTerminalFailure(state, request, exception);
            }
            if (verdict.disposition() == AnswerDisposition.REJECTED) {
                String rejection = rejectionDescription(verdict);
                state = commit(state, new AgentEvent.AnswerRejected(
                        state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), verdict));
                state = recordAnswerRejectionObservations(state, pending.document(), verdict);
                if (pending.finalResponseMode()) {
                    AgentLoopResult result = conclude(
                            state, request, RunOutcome.INCONCLUSIVE, INCONCLUSIVE_RESPONSE, Optional.empty());
                    return new TerminalExecution(state, Optional.empty(), true, Optional.of(result));
                }
                return new TerminalExecution(state, Optional.of(rejection), false, Optional.empty());
            }
            acceptance = AnswerAcceptance.llm(verdict);
        } else if (pending.verificationMode() == AnswerVerificationMode.CONTRACT_ONLY
                && verificationResult instanceof AnswerVerificationResult.ContractAccepted) {
            acceptance = AnswerAcceptance.contractOnly();
        } else {
            return integrationContractTerminalFailure(state, request,
                    new AnswerVerificationContractException("answer verification returned an incompatible result"));
        }
        String rendered = pending.document().renderParagraphs();
        ConversationTurn turn = new ConversationTurn(
                request.runId(), request.question(), rendered, ConversationTurnType.ANSWER);
        try {
            state = commitTerminalAcceptance(state, new AgentEvent.AnswerAccepted(
                    state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), pending.document(),
                    acceptance, request.sessionId(), turn, pending.finalResponseMode()));
        } catch (TerminalAcceptanceCancelledException exception) {
            AgentRunState abandoned = abandonAnswerVerification(
                    currentState, AnswerVerificationAbandonReason.CANCELLED);
            AgentLoopResult result = conclude(
                    abandoned, request, RunOutcome.CANCELLED, CANCELLED_RESPONSE, Optional.empty());
            return new TerminalExecution(abandoned, Optional.empty(), false, Optional.of(result));
        }
        sessionPort.append(request.sessionId(), turn);
        AgentLoopResult result = conclude(
                state, request, acceptance.expectedOutcome(), rendered, Optional.of(pending.document()));
        return new TerminalExecution(state, Optional.empty(), false, Optional.of(result));
    }

    private AgentRunState abandonAnswerVerification(
            AgentRunState state,
            AnswerVerificationAbandonReason reason) {
        return commit(state, new AgentEvent.AnswerVerificationAbandoned(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), reason));
    }

    private TerminalExecution integrationContractTerminalFailure(
            AgentRunState state,
            AgentLoopRequest request,
            RuntimeException exception) {
        AgentLoopResult result = concludeIntegrationFailure(state, request, exception);
        return new TerminalExecution(state, Optional.empty(), false, Optional.of(result));
    }

    private AgentLoopResult concludeIntegrationFailure(
            AgentRunState state,
            AgentLoopRequest request,
            RuntimeException exception) {
        try {
            AgentRunState abandoned = state.pendingAnswerVerification().isPresent()
                    ? abandonAnswerVerification(state, AnswerVerificationAbandonReason.INTEGRATION_CONTRACT_FAILURE)
                    : state;
            return conclude(abandoned, request, RunOutcome.FAILED, FAILED_RESPONSE, Optional.empty());
        } catch (AgentTransitionConflictException | AgentLoopException | AgentRunInProgressException commitFailure) {
            AnswerExecutionContractException contractFailure = new AnswerExecutionContractException(
                    "integration contract failure could not be concluded", commitFailure);
            contractFailure.addSuppressed(exception);
            throw contractFailure;
        }
    }

    private AgentLoopResult acceptClarification(
            AgentLoopRequest request,
            AgentRunState currentState,
            ClarifyAction clarification,
            boolean finalResponseMode) {
        ConversationTurn turn = new ConversationTurn(
                request.runId(), request.question(), clarification.question(), ConversationTurnType.CLARIFICATION);
        AgentRunState state;
        try {
            state = commitTerminalAcceptance(currentState, new AgentEvent.ClarificationAccepted(
                    currentState.runId(), currentState.currentAttempt().attemptId(), currentState.stateRevision(),
                    clarification, request.sessionId(), turn, finalResponseMode));
        } catch (TerminalAcceptanceCancelledException exception) {
            return conclude(currentState, request, RunOutcome.CANCELLED, CANCELLED_RESPONSE, Optional.empty());
        }
        sessionPort.append(request.sessionId(), turn);
        return conclude(
                state,
                request,
                RunOutcome.INCONCLUSIVE,
                clarification.question(),
                Optional.empty());
    }

    private RevisionResolution resolveRevisions(
            RevisionVector current,
            List<IssuedCandidate> selectedCandidates) {
        LinkedHashSet<RepositoryId> selectedRepositories = new LinkedHashSet<>();
        for (IssuedCandidate candidate : selectedCandidates) {
            selectedRepositories.add(candidate.candidate().repositoryId());
        }
        RevisionVector revisions = current;
        boolean drifted = false;
        Optional<RepositoryRevisionFailure> unavailable = Optional.empty();
        for (RepositoryId repositoryId : selectedRepositories) {
            RepositoryRevisionResult result = Objects.requireNonNull(
                    repositoryRevisionPort.currentRevision(repositoryId),
                    "repository revision port must return a result");
            if (result instanceof RepositoryRevisionResult.Failed failed) {
                if (unavailable.isEmpty()) {
                    unavailable = Optional.of(failed.failure());
                }
                continue;
            }
            RepositoryRevision revision = ((RepositoryRevisionResult.Ready) result).revision();
            Optional<RepositoryRevision> pinned = current.revisionOf(repositoryId);
            if (pinned.isPresent() && !pinned.orElseThrow().equals(revision)) {
                drifted = true;
            } else if (pinned.isEmpty()) {
                revisions = revisions.pin(repositoryId, revision);
            }
        }
        return new RevisionResolution(revisions, drifted, drifted ? Optional.empty() : unavailable);
    }

    private AgentRunState reject(
            AgentRunState state,
            Optional<AgentAction> action,
            String description,
            boolean finalResponseMode) {
        return commit(state, new AgentEvent.ActionRejected(
                state.runId(),
                state.currentAttempt().attemptId(),
                state.stateRevision(),
                action,
                description,
                finalResponseMode));
    }

    private AgentRunState recordRuntimeObservation(
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
        return commit(state, new AgentEvent.ObservationRecorded(
                state.runId(),
                state.currentAttempt().attemptId(),
                state.stateRevision(),
                observation));
    }

    private AgentRunState recordCapabilityFailureObservation(
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
        return commit(state, new AgentEvent.ObservationRecorded(
                state.runId(),
                state.currentAttempt().attemptId(),
                state.stateRevision(),
                observation));
    }

    private AgentRunState recordAnswerRejectionObservations(
            AgentRunState initialState,
            AnswerDocument document,
            AnswerVerdict verdict) {
        AgentRunState state = initialState;
        Map<StatementId, AnswerStatement> statements = new LinkedHashMap<>();
        document.statements().forEach(statement -> statements.put(statement.statementId(), statement));
        for (StatementVerdict statementVerdict : verdict.statementVerdicts()) {
            if (statementVerdict.status() == StatementVerdictStatus.UNSUPPORTED) {
                AnswerStatement statement = statements.get(statementVerdict.statementId());
                state = recordRuntimeObservation(
                        state,
                        ObservationCode.UNSUPPORTED_CLAIM,
                        statementVerdict.description(),
                        Set.of(),
                        statement.citations(),
                        "answer-verifier");
            }
        }
        for (String unaddressedPart : verdict.unaddressedParts()) {
            state = recordRuntimeObservation(
                    state, ObservationCode.UNADDRESSED_PART, unaddressedPart,
                    Set.of(), Set.of(), "answer-verifier");
        }
        for (String blockingUncertainty : verdict.blockingUncertainties()) {
            state = recordRuntimeObservation(
                    state, ObservationCode.BLOCKING_UNCERTAINTY, blockingUncertainty,
                    Set.of(), Set.of(), "answer-verifier");
        }
        for (String rejectionReason : verdict.rejectionReasons()) {
            state = recordRuntimeObservation(
                    state, ObservationCode.ANSWER_REJECTION_REASON, rejectionReason,
                    Set.of(), Set.of(), "answer-verifier");
        }
        return state;
    }

    private AgentLoopResult conclude(
            AgentRunState state,
            AgentLoopRequest request,
            RunOutcome outcome,
            String responseText,
            Optional<AnswerDocument> document) {
        AgentRunState concluded = commit(state, new AgentEvent.RunConcluded(
                state.runId(),
                state.currentAttempt().attemptId(),
                state.stateRevision(),
                outcome,
                state.pendingTerminalResponse().isEmpty()));
        return loopResult(concluded, responseText, document);
    }

    private AgentLoopResult loopResult(
            AgentRunState state,
            String responseText,
            Optional<AnswerDocument> document) {
        RunResponseKind responseKind = RunResponseKind.RUNTIME_NOTICE;
        Optional<AnswerVerificationBasis> verificationBasis = Optional.empty();
        if (state.pendingTerminalResponse().isPresent()) {
            PendingTerminalResponse pending = state.pendingTerminalResponse().orElseThrow();
            if (pending instanceof PendingTerminalResponse.Answer answer) {
                responseKind = RunResponseKind.ANSWER;
                verificationBasis = Optional.of(answer.acceptance().verificationBasis());
            } else {
                responseKind = RunResponseKind.CLARIFICATION;
            }
        }
        return new AgentLoopResult(
                state.runId(),
                state.finalOutcome().orElseThrow(),
                responseText,
                document,
                responseKind,
                verificationBasis,
                state.currentAttempt().revisionVector());
    }

    private AgentRunState commitContext(AgentRunState state, RunAttempt context) {
        return commit(state, new AgentEvent.ContextIssued(
                state.runId(),
                state.currentAttempt().attemptId(),
                state.stateRevision(),
                context.revisionVector(),
                context.issuedCapabilities(),
                context.issuedCandidates(),
                context.issuedEvidence(),
                context.observations()));
    }

    private AgentRunState commit(AgentRunState state, AgentEvent event) {
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

    private AgentRunState bootstrap(AgentRunState initialState, RunAttempt initialContext) {
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

    private AgentRunState commitTerminalAcceptance(AgentRunState state, AgentEvent event) {
        try {
            return transitionCommitter.applyTerminalAcceptance(state, event);
        } catch (TerminalAcceptanceCancelledException exception) {
            throw exception;
        } catch (AgentTransitionCommitException exception) {
            throw new AgentLoopException("agent loop terminal acceptance could not be committed", state, event, exception);
        }
    }

    private AgentPromptContext prompt(
            AgentLoopRequest request,
            SessionHistory sessionHistory,
            AgentRunState state,
            Optional<String> latestRejection,
            boolean finalResponseMode) {
        return new AgentPromptContext(
                request.question(),
                sessionHistory,
                state.runId(),
                state.currentAttempt().attemptId(),
                state.currentAttempt().issuedCapabilities(),
                state.currentAttempt().issuedCandidates(),
                state.currentAttempt().issuedEvidence(),
                state.currentAttempt().observations(),
                latestRejection,
                state.budget(),
                finalResponseMode);
    }

    private AgentValidationContext validationContext(
            AgentRunState state,
            boolean finalResponseMode) {
        return new AgentValidationContext(
                state.currentAttempt().issuedCapabilities(),
                state.currentAttempt().issuedCandidates(),
                state.currentAttempt().issuedEvidence(),
                state.currentAttempt().observations(),
                currentBinding(state),
                state.budget(),
                finalResponseMode);
    }

    private HandleBinding currentBinding(AgentRunState state) {
        return new HandleBinding(
                state.runId(),
                state.currentAttempt().attemptId(),
                state.currentAttempt().revisionVector());
    }

    private CapabilityDescriptor findCapability(
            RunAttempt attempt,
            QueryAction action) {
        return attempt.issuedCapabilities().entrySet().stream()
                .filter(entry -> entry.getKey().value().equals(action.capability().value()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("accepted capability is absent after context reissue"));
    }

    private IssuedCandidate findIssuedCandidate(
            RunAttempt attempt,
            String handleValue) {
        return attempt.issuedCandidates().values().stream()
                .filter(candidate -> candidate.handle().value().equals(handleValue))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("accepted candidate is absent after context reissue"));
    }

    private ObservationId nextObservationId(AgentRunState state) {
        return new ObservationId(
                state.currentAttempt().attemptId().value()
                        + ":O"
                        + (state.currentAttempt().observations().size() + 1));
    }

    private String rejectionDescription(AnswerVerdict verdict) {
        List<String> descriptions = new ArrayList<>();
        verdict.unaddressedParts().forEach(part -> descriptions.add("unaddressed part: " + part));
        verdict.blockingUncertainties().forEach(uncertainty ->
                descriptions.add("blocking uncertainty: " + uncertainty));
        verdict.rejectionReasons().forEach(reason -> descriptions.add("rejection reason: " + reason));
        if (!descriptions.isEmpty()) {
            return String.join("; ", descriptions);
        }
        return "answer verifier rejected the proposed document";
    }

    private boolean normalBudgetExhausted(AttemptBudget budget) {
        return !budget.hasAgentStepRemaining()
                || !budget.hasQueryExecutionRemaining()
                || !budget.hasActionRejectionRemaining();
    }

    private static String verificationResultCategory(AnswerVerificationResult result) {
        if (result instanceof AnswerVerificationResult.LlmVerdict) {
            return "LLM_VERDICT";
        }
        return "CONTRACT_ACCEPTED";
    }

    private static void logVerificationOperation(
            AgentRunState state,
            AnswerVerificationMode mode,
            String resultCategory,
            long startedNanos) {
        Level level = "LLM_VERDICT".equals(resultCategory) || "CONTRACT_ACCEPTED".equals(resultCategory)
                ? Level.INFO : Level.WARNING;
        LOGGER.log(level,
                "answer verification operation=VERIFY runId={0} attemptId={1} mode={2} resultCategory={3} elapsedMs={4}",
                new Object[]{
                        state.runId().value(),
                        state.currentAttempt().attemptId().value(),
                        mode.name(),
                        resultCategory,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)});
    }

    private record RevisionResolution(
            RevisionVector revisions,
            boolean drifted,
            Optional<RepositoryRevisionFailure> failure) {
    }

    private record InitialClaim(
            Optional<AgentRunState> state,
            Optional<ActiveExecution> execution,
            Optional<AgentLoopResult> result) {
    }

    private record PersistedDispatch(Optional<ActiveExecution> execution, Optional<AgentLoopResult> result) {
    }

    private record BootstrapPreparation(
            AgentRunState initialState,
            RunAttempt initialContext,
            SessionHistory sessionHistory,
            List<CapabilityDescriptor> capabilityCatalog,
            List<RepositoryDescriptor> repositoryCatalog,
            Set<RepositoryId> catalogRepositoryIds) {
    }

    private record ActiveExecution(
            AgentRunState state,
            SessionHistory sessionHistory,
            List<CapabilityDescriptor> capabilityCatalog,
            List<RepositoryDescriptor> repositoryCatalog,
            Set<RepositoryId> catalogRepositoryIds,
            int attemptSequence,
            Optional<String> latestRejection,
            boolean forcedFinalResponse) {
    }

    private record PendingVerificationResume(Optional<ActiveExecution> execution, Optional<AgentLoopResult> result) {
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

    private record QueryExecution(
            AgentRunState state,
            int attemptSequence,
            boolean forceFinalResponse,
            Optional<String> latestRejection,
            Optional<AgentLoopResult> result) {
    }

    private record TerminalExecution(
            AgentRunState state,
            Optional<String> latestRejection,
            boolean forceFinalResponse,
            Optional<AgentLoopResult> result) {
    }
}
