package com.java.system.agent.answering.application;

import com.java.system.agent.answering.application.state.AgentTransitionCommitter;
import com.java.system.agent.answering.application.validation.ActionValidation;
import com.java.system.agent.answering.application.validation.AgentActionValidator;
import com.java.system.agent.answering.application.validation.AgentValidationContext;
import com.java.system.agent.answering.application.validation.AnswerDocumentValidation;
import com.java.system.agent.answering.application.validation.AnswerDocumentValidator;
import com.java.system.agent.answering.application.validation.AnswerVerdictValidator;
import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.action.AnswerAction;
import com.java.system.agent.answering.domain.action.ClarifyAction;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.answer.AnswerDisposition;
import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.AnswerVerdict;
import com.java.system.agent.answering.domain.answer.AnswerAcceptance;
import com.java.system.agent.answering.domain.answer.AnswerVerificationBasis;
import com.java.system.agent.answering.domain.answer.AnswerVerificationMode;
import com.java.system.agent.answering.domain.answer.StatementId;
import com.java.system.agent.answering.domain.answer.StatementVerdict;
import com.java.system.agent.answering.domain.answer.StatementVerdictStatus;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.candidate.AnalysisCandidate;
import com.java.system.agent.answering.domain.candidate.RepositoryCandidate;
import com.java.system.agent.answering.domain.conversation.ConversationTurn;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.conversation.ConversationTurnType;
import com.java.system.agent.answering.domain.evidence.IssuedEvidence;
import com.java.system.agent.answering.domain.evidence.EvidenceRef;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.handle.EvidenceHandle;
import com.java.system.agent.answering.domain.observation.AgentObservation;
import com.java.system.agent.answering.domain.observation.ObservationCode;
import com.java.system.agent.answering.domain.run.AgentEvent;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.AgentRunStatus;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.RunAttempt;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.run.RunFailureReason;
import com.java.system.agent.answering.domain.run.RunResponseKind;
import com.java.system.agent.answering.domain.run.PendingTerminalResponse;
import com.java.system.agent.answering.domain.run.PendingAnswerVerification;
import com.java.system.agent.answering.domain.run.AnswerVerificationAbandonReason;
import com.java.system.agent.answering.domain.run.ExecutionDeferral;
import com.java.system.agent.answering.domain.run.RunRequestIdentity;
import com.java.system.agent.answering.domain.run.RuntimeNoticeReason;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.port.out.AgentActionPort;
import com.java.system.agent.answering.port.out.AgentActionContractException;
import com.java.system.agent.answering.port.out.AgentActionProposal;
import com.java.system.agent.answering.port.out.AgentPromptContext;
import com.java.system.agent.answering.port.out.CapabilityExecutionPort;
import com.java.system.agent.answering.port.out.CapabilityExecutionContractException;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.answering.port.out.CapabilityInvocation;
import com.java.system.agent.answering.port.out.AnalysisAttemptIdGenerator;
import com.java.system.agent.answering.port.out.AnalysisCancellationPort;
import com.java.system.agent.answering.port.out.AnswerVerificationContext;
import com.java.system.agent.answering.port.out.AnswerVerificationPort;
import com.java.system.agent.answering.port.out.AnswerVerificationResult;
import com.java.system.agent.answering.port.out.AnswerVerificationUnavailableException;
import com.java.system.agent.answering.port.out.AnswerVerificationContractException;
import com.java.system.agent.answering.port.in.AnswerExecutionUnavailableException;
import com.java.system.agent.answering.port.in.AnswerExecutionContractException;
import com.java.system.agent.answering.port.in.AnswerExecutionContractFailure;
import com.java.system.agent.answering.port.out.CapabilityCatalogPort;
import com.java.system.agent.answering.port.out.SessionPort;
import com.java.system.agent.answering.port.out.RepositoryCatalogPort;
import com.java.system.agent.answering.port.out.RepositoryDescriptor;
import com.java.system.agent.answering.port.out.RepositoryRevisionPort;
import com.java.system.agent.answering.port.out.RepositoryRevisionResult;
import com.java.system.agent.answering.port.out.RepositoryRevisionFailure;
import com.java.system.agent.answering.port.out.RepositoryRevisionContractException;
import com.java.system.agent.answering.port.out.AgentTransitionConflictException;
import com.java.system.agent.answering.port.out.TerminalAcceptanceCancelledException;
import com.java.system.agent.answering.port.in.AnswerExecutionMode;
import com.java.system.agent.answering.port.in.AnalysisExecutionDeferredException;
import com.java.system.agent.answering.port.out.ExternalExecutionDeferredException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 唯一推進 bounded action、capability execution、verification 與 terminal persistence 的 orchestrator
 */
public final class ValidatedAgentLoop {

    public static final String INCONCLUSIVE_RESPONSE = "目前資訊不足以產生可驗證的回答";
    public static final String PLANNING_BUDGET_EXHAUSTED_RESPONSE = "本次分析已達處理上限，請縮小問題範圍後重試";
    public static final String FAILED_RESPONSE = "分析流程發生錯誤，未回傳未驗證內容";
    public static final String CANCELLED_RESPONSE = "分析已取消";
    private final AgentActionPort actionPort;
    private final AnswerVerificationMode answerVerificationMode;
    private final SessionPort sessionPort;
    private final AnalysisCancellationPort cancellationPort;
    private final AnalysisAttemptIdGenerator attemptIdGenerator;
    private final AgentActionValidator actionValidator;
    private final AnswerDocumentValidator documentValidator;
    private final AnswerVerdictValidator verdictValidator;
    private final AgentRunTransitions transitions;
    private final AgentLoopTelemetry telemetry;
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
        this.answerVerificationMode = Objects.requireNonNull(
                answerVerificationMode, "answer verification mode must not be null");
        this.sessionPort = Objects.requireNonNull(sessionPort, "session port must not be null");
        this.cancellationPort = Objects.requireNonNull(
                cancellationPort, "analysis cancellation port must not be null");
        this.attemptIdGenerator = Objects.requireNonNull(
                attemptIdGenerator, "analysis attempt ID generator must not be null");
        this.actionValidator = Objects.requireNonNull(actionValidator, "agent action validator must not be null");
        this.documentValidator = Objects.requireNonNull(
                documentValidator, "answer document validator must not be null");
        this.verdictValidator = Objects.requireNonNull(
                verdictValidator, "answer verdict validator must not be null");
        this.transitions = new AgentRunTransitions(transitionCommitter);
        this.telemetry = new AgentLoopTelemetry(
                capabilityExecutionPort,
                verificationPort,
                capabilityCatalogPort,
                repositoryCatalogPort,
                repositoryRevisionPort);
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
                if (request.executionMode() == AnswerExecutionMode.CAPACITY_RESUME) {
                    execution = prepareCapacityResumeExecution(request, persistedState);
                } else {
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
            }
        } else {
            if (request.executionMode() == AnswerExecutionMode.CAPACITY_RESUME) {
                throw new AnswerExecutionContractException(
                        "capacity resume requires a persisted nonterminal agent run");
            }
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
                    Optional.empty()));
        }
        AgentRunState state = execution.state();
        SessionHistory sessionHistory = execution.sessionHistory();
        List<CapabilityPolicy> capabilityCatalog = execution.capabilityCatalog();
        List<RepositoryDescriptor> repositoryCatalog = execution.repositoryCatalog();
        Set<RepositoryId> catalogRepositoryIds = execution.catalogRepositoryIds();
        int attemptSequence = execution.attemptSequence();

        Optional<String> latestRejection = execution.latestRejection();
        while (true) {
            if (cancellationPort.isCancellationRequested(request.runId())) {
                return conclude(state, request, RunOutcome.CANCELLED, CANCELLED_RESPONSE, Optional.empty());
            }
            Optional<RuntimeNoticeReason> exhaustedBudgetReason = exhaustedBudgetReason(state.budget());
            if (exhaustedBudgetReason.isPresent()) {
                return concludeRuntimeNotice(state, request, exhaustedBudgetReason.orElseThrow());
            }
            AgentActionProposal proposal;
            try {
                proposal = Objects.requireNonNull(
                        actionPort.nextAction(prompt(request, sessionHistory, state, latestRejection)),
                        "agent action port must return a proposal");
            } catch (AgentActionContractException exception) {
                concludeIntegrationFailure(
                        state, request, exception, Optional.of(RunFailureReason.PLANNING_TOOL_CONTRACT));
                throw new AnswerExecutionContractException(
                        AnswerExecutionContractFailure.PLANNING_TOOL_CONTRACT,
                        "planning tool contract failed", exception);
            } catch (ExternalExecutionDeferredException exception) {
                throw deferredExecution(exception);
            }
            if (cancellationPort.isCancellationRequested(request.runId())) {
                return conclude(state, request, RunOutcome.CANCELLED, CANCELLED_RESPONSE, Optional.empty());
            }
            if (proposal instanceof AgentActionProposal.Malformed malformed) {
                state = reject(state, Optional.empty(), malformed.description());
                state = transitions.recordRuntimeObservation(state, ObservationCode.ACTION_REJECTED, malformed.description(),
                        Set.of(), Set.of(), "agent-action-parser");
                latestRejection = Optional.of(malformed.description());
                continue;
            }

            AgentAction action = ((AgentActionProposal.Proposed) proposal).action();
            ActionValidation validation = actionValidator.validate(action, validationContext(state));
            if (validation instanceof ActionValidation.Rejected rejected) {
                state = reject(state, Optional.of(action), rejected.description());
                state = transitions.recordRuntimeObservation(state, ObservationCode.ACTION_REJECTED, rejected.description(),
                        Set.of(), Set.of(), "agent-action-validator");
                latestRejection = Optional.of(rejected.description());
                continue;
            }
            if (action instanceof QueryAction queryAction) {
                QueryExecution queryExecution = executeQuery(
                        request,
                        state,
                        queryAction,
                        ((ActionValidation.Accepted) validation).resolvedCandidates(),
                        attemptSequence,
                        capabilityCatalog,
                        repositoryCatalog,
                        catalogRepositoryIds);
                if (queryExecution.result().isPresent()) {
                    return queryExecution.result().orElseThrow();
                }
                state = queryExecution.state();
                attemptSequence = queryExecution.attemptSequence();
                latestRejection = queryExecution.latestRejection();
                continue;
            }
            if (action instanceof AnswerAction answerAction) {
                TerminalExecution terminal = executeAnswer(
                        request, sessionHistory, state, answerAction);
                if (terminal.result().isPresent()) {
                    return terminal.result().orElseThrow();
                }
                state = terminal.state();
                latestRejection = terminal.latestRejection();
                continue;
            }
            ClarifyAction clarification = (ClarifyAction) action;
            return acceptClarification(request, state, clarification);
        }
    }

    private Optional<AgentRunState> findPersistedState(AgentLoopRequest request) {
        return transitions.findByRunId(request.runId());
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
                AgentRunState abandoned = transitions.abandonAnswerVerification(
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
                new RunRequestIdentity(request.sessionId().value(), request.participant(), request.question()));
        SessionHistory sessionHistory = Objects.requireNonNull(sessionPort.read(request.sessionId()),
                "session port must return session history");
        List<CapabilityPolicy> capabilityCatalog = telemetry.loadCapabilities(initialState);
        List<RepositoryDescriptor> repositoryCatalog = telemetry.loadRepositories(initialState);
        RunAttempt initialContext = contextIssuer.issueInitial(
                request.runId(), attemptId, RevisionVector.empty(), capabilityCatalog, repositoryCatalog);
        Set<RepositoryId> catalogRepositoryIds = repositoryCatalog.stream()
                .map(repositoryDescriptor -> repositoryDescriptor.repositoryId())
                .collect(Collectors.toUnmodifiableSet());
        return new BootstrapPreparation(
                initialState, initialContext, sessionHistory, capabilityCatalog, repositoryCatalog, catalogRepositoryIds);
    }

    private InitialClaim claimInitialRun(
            AgentLoopRequest request,
            AgentRunState initialState,
            RunAttempt initialContext) {
        try {
            return new InitialClaim(Optional.of(transitions.bootstrap(initialState, initialContext)), Optional.empty(), Optional.empty());
        } catch (AgentTransitionConflictException exception) {
            AgentRunState authoritative = transitions.findByRunId(request.runId())
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
        List<CapabilityPolicy> capabilityCatalog = telemetry.loadCapabilities(persistedState);
        List<RepositoryDescriptor> repositoryCatalog = telemetry.loadRepositories(persistedState);
        Set<RepositoryId> catalogRepositoryIds = repositoryCatalog.stream()
                .map(repositoryDescriptor -> repositoryDescriptor.repositoryId())
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
        AgentRunState contextualized = transitions.commitContext(restarted, restartedContext);
        return new ActiveExecution(
                contextualized,
                sessionHistory,
                capabilityCatalog,
                repositoryCatalog,
                catalogRepositoryIds,
                contextualized.attemptSequence(),
                Optional.empty());
    }

    private ActiveExecution prepareCapacityResumeExecution(AgentLoopRequest request, AgentRunState persistedState) {
        validateRequestIdentity(request, persistedState);
        if (persistedState.status() != AgentRunStatus.RUNNING) {
            throw new AnswerExecutionContractException(
                    "capacity resume requires a persisted running agent run");
        }
        SessionHistory sessionHistory = Objects.requireNonNull(sessionPort.read(request.sessionId()),
                "session port must return session history");
        List<CapabilityPolicy> capabilities = List.copyOf(
                persistedState.currentAttempt().issuedCapabilities().values());
        Map<RepositoryId, RepositoryDescriptor> repositories = new LinkedHashMap<>();
        for (IssuedCandidate issued : persistedState.currentAttempt().issuedCandidates().values()) {
            if (issued.candidate() instanceof RepositoryCandidate repository) {
                repositories.putIfAbsent(repository.repositoryId(),
                        new RepositoryDescriptor(repository.repositoryId(), repository.description()));
            }
        }
        return new ActiveExecution(
                persistedState,
                sessionHistory,
                capabilities,
                List.copyOf(repositories.values()),
                Set.copyOf(repositories.keySet()),
                persistedState.attemptSequence(),
                Optional.empty());
    }

    private PendingVerificationResume resumePendingVerification(AgentLoopRequest request, AgentRunState persisted) {
        validateRequestIdentity(request, persisted);
        SessionHistory sessionHistory = Objects.requireNonNull(sessionPort.read(request.sessionId()),
                "session port must return session history");
        TerminalExecution terminal = verifyPendingAnswer(request, sessionHistory, persisted);
        if (terminal.result().isPresent()) {
            return new PendingVerificationResume(Optional.empty(), terminal.result());
        }
        List<CapabilityPolicy> capabilities = List.copyOf(persisted.currentAttempt().issuedCapabilities().values());
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
                terminal.latestRejection())), Optional.empty());
    }

    private AgentRunState restartPersistedAttempt(AgentLoopRequest request, AgentRunState persistedState) {
        AgentRunState restarting = persistedState;
        if (persistedState.status() == AgentRunStatus.RUNNING) {
            restarting = transitions.apply(persistedState, new AgentEvent.AttemptInvalidated(
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
        return transitions.apply(restarting, new AgentEvent.AttemptStarted(
                restarting.runId(),
                restarting.currentAttempt().attemptId(),
                restarting.stateRevision(),
                RunAttempt.empty(nextAttemptId)));
    }

    private PersistedDispatch resolveRetryConflict(AgentLoopRequest request, AgentTransitionConflictException exception) {
        AgentRunState authoritative = transitions.findByRunId(request.runId())
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
            AgentRunState authoritative = transitions.findByRunId(request.runId())
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
                || !state.requestIdentity().participant().equals(request.participant())
                || !state.requestIdentity().questionText().equals(request.question())) {
            throw new IllegalArgumentException("incoming request does not match the persisted request identity");
        }
    }

    private AgentLoopResult concludedResult(AgentRunState state) {
        if (state.failureReason().map(RunFailureReason.PLANNING_TOOL_CONTRACT::equals).orElse(false)) {
            throw new AnswerExecutionContractException(
                    AnswerExecutionContractFailure.PLANNING_TOOL_CONTRACT,
                    "planning tool contract failed",
                    null);
        }
        RunOutcome outcome = state.finalOutcome().orElseThrow();
        if (state.pendingTerminalResponse().isPresent()) {
            PendingTerminalResponse pending = state.pendingTerminalResponse().orElseThrow();
            return loopResult(state, pending.turn().assistantMessage(), pendingAnswerDocument(pending));
        }
        return loopResult(state, runtimeNoticeResponse(state.runtimeNoticeReason(), outcome), Optional.empty());
    }

    private Optional<AnswerDocument> pendingAnswerDocument(PendingTerminalResponse pending) {
        if (pending instanceof PendingTerminalResponse.Answer answer) {
            return Optional.of(answer.document());
        }
        return Optional.empty();
    }

    private String runtimeNoticeResponse(Optional<RuntimeNoticeReason> reason, RunOutcome outcome) {
        return switch (outcome) {
            case COMPLETED -> throw new IllegalStateException("completed agent run must retain its accepted answer");
            case INCONCLUSIVE -> switch (reason.orElseThrow(
                    () -> new IllegalStateException("inconclusive runtime notice requires a reason"))) {
                case INSUFFICIENT_VERIFIABLE_INFORMATION -> INCONCLUSIVE_RESPONSE;
                case AGENT_STEP_BUDGET_EXHAUSTED,
                        QUERY_EXECUTION_BUDGET_EXHAUSTED,
                        ACTION_REJECTION_BUDGET_EXHAUSTED -> PLANNING_BUDGET_EXHAUSTED_RESPONSE;
            };
            case FAILED -> FAILED_RESPONSE;
            case CANCELLED -> CANCELLED_RESPONSE;
        };
    }

    private QueryExecution executeQuery(
            AgentLoopRequest request,
            AgentRunState currentState,
            QueryAction action,
            List<IssuedCandidate> selectedValues,
            int attemptSequence,
            List<CapabilityPolicy> capabilityCatalog,
            List<RepositoryDescriptor> repositoryCatalog,
            Set<RepositoryId> catalogRepositoryIds) {
        AgentRunState state = currentState;
        List<String> selectedHandleValues = action.candidates().stream()
                .map(candidateHandleReference -> candidateHandleReference.value())
                .toList();
        RevisionResolution revisionResolution;
        try {
            revisionResolution = resolveRevisions(state, state.currentAttempt().revisionVector(), selectedValues);
        } catch (RepositoryRevisionContractException exception) {
            return integrationContractFailure(request, state, attemptSequence, exception);
        }
        if (revisionResolution.drifted()) {
            boolean restartAllowed = state.budget().hasRevisionRestartRemaining();
            state = transitions.apply(state, new AgentEvent.AttemptInvalidated(
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
            state = transitions.apply(state, new AgentEvent.AttemptStarted(
                    state.runId(),
                    invalidatedAttemptId,
                    state.stateRevision(),
                    RunAttempt.empty(nextAttemptId)));
            state = transitions.commitContext(state, restartedContext);
            if (!restartAllowed) {
                String description = "repository revision changed and stale context was discarded";
                state = transitions.recordRuntimeObservation(
                        state,
                        ObservationCode.CONFLICTING_EVIDENCE,
                        description,
                        Set.of(),
                        Set.of(),
                        "repository-revision-validator");
                return new QueryExecution(
                        state,
                        nextAttemptSequence,
                        Optional.of(description + "; restart budget is exhausted"),
                        Optional.empty());
            }
            return new QueryExecution(
                    state,
                    nextAttemptSequence,
                    Optional.of("repository revision changed; a new attempt was started"),
                    Optional.empty());
        }
        if (revisionResolution.failure().isPresent()) {
            RepositoryRevisionFailure failure = revisionResolution.failure().orElseThrow();
            state = transitions.apply(state, new AgentEvent.ActionAccepted(
                    state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), action));
            state = transitions.apply(state, new AgentEvent.QueryBudgetConsumed(
                    state.runId(), state.currentAttempt().attemptId(), state.stateRevision()));
            state = transitions.recordRuntimeObservation(state, ObservationCode.EXECUTION_FAILED,
                    failure.description(), Set.of(), Set.of(), failure.operationSource());
            return new QueryExecution(state, attemptSequence, Optional.empty(), Optional.empty());
        }
        state = transitions.apply(state, new AgentEvent.ActionAccepted(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), action));
        state = transitions.apply(state, new AgentEvent.QueryBudgetConsumed(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision()));
        if (!revisionResolution.revisions().equals(state.currentAttempt().revisionVector())) {
            RunAttempt rebound;
            try {
                rebound = contextIssuer.reissue(
                        state.runId(), state.currentAttempt(), revisionResolution.revisions());
            } catch (CapabilityExecutionContractException exception) {
                return integrationContractFailure(request, state, attemptSequence, exception);
            }
            state = transitions.commitContext(state, rebound);
        }
        if (cancellationPort.isCancellationRequested(request.runId())) {
            AgentLoopResult result = conclude(
                    state, request, RunOutcome.CANCELLED, CANCELLED_RESPONSE, Optional.empty());
            return new QueryExecution(state, attemptSequence, Optional.empty(), Optional.of(result));
        }
        RunAttempt queryContext = state.currentAttempt();
        List<IssuedCandidate> reboundSelection = selectedHandleValues.stream()
                .map(handleValue -> findIssuedCandidate(queryContext, handleValue))
                .toList();
        CapabilityInvocation capabilityInvocation = new CapabilityInvocation(
                findCapability(queryContext, action),
                reboundSelection,
                action.questionToResolve(),
                action.payload(),
                queryContext.revisionVector());
        CapabilityExecutionResult result;
        try {
            result = telemetry.executeCapability(state, capabilityInvocation);
        } catch (CapabilityExecutionContractException exception) {
            concludeIntegrationFailure(
                    state, request, exception, Optional.of(RunFailureReason.PLANNING_TOOL_CONTRACT));
            throw new AnswerExecutionContractException(
                    AnswerExecutionContractFailure.PLANNING_TOOL_CONTRACT,
                    "planning tool contract failed", exception);
        }
        if (result instanceof CapabilityExecutionResult.Failed failed) {
            state = transitions.recordCapabilityFailureObservation(state, ObservationCode.EXECUTION_FAILED,
                    failed.failure().description(), Set.of(), Set.of(), failed.failure().operationSource());
            return new QueryExecution(state, attemptSequence, Optional.empty(), Optional.empty());
        }
        CapabilityExecutionResult.Succeeded succeeded = (CapabilityExecutionResult.Succeeded) result;
        SuccessfulCapabilityResultValidation validation;
        try {
            contextIssuer.validateCapabilityRepositories(succeeded, catalogRepositoryIds);
            validation = validateSuccessfulCapabilityResult(capabilityInvocation, succeeded);
        } catch (CapabilityExecutionContractException exception) {
            return integrationContractFailure(request, state, attemptSequence, exception);
        }
        if (validation.revisionConflict().isPresent()) {
            state = transitions.recordRuntimeObservation(
                    state,
                    ObservationCode.CONFLICTING_EVIDENCE,
                    validation.revisionConflict().orElseThrow(),
                    Set.of(),
                    Set.of(),
                    "capability-result-validator");
            return new QueryExecution(state, attemptSequence, Optional.empty(), Optional.empty());
        }
        RunAttempt resultContext = state.currentAttempt();
        if (capabilityInvocation.candidates().isEmpty()) {
            PostResultRevisionResolution resolution;
            try {
                resolution = resolveUnscopedResultRevisions(
                        state,
                        state.currentAttempt().revisionVector(),
                        validation.declaredRevisions());
            } catch (RepositoryRevisionContractException exception) {
                return integrationContractFailure(request, state, attemptSequence, exception);
            }
            if (resolution.revisionConflict().isPresent()) {
                state = transitions.recordRuntimeObservation(
                        state,
                        ObservationCode.CONFLICTING_EVIDENCE,
                        resolution.revisionConflict().orElseThrow(),
                        Set.of(),
                        Set.of(),
                        "capability-result-validator");
                return new QueryExecution(state, attemptSequence, Optional.empty(), Optional.empty());
            }
            if (resolution.failure().isPresent()) {
                RepositoryRevisionFailure failure = resolution.failure().orElseThrow();
                state = transitions.recordRuntimeObservation(
                        state,
                        ObservationCode.EXECUTION_FAILED,
                        failure.description(),
                        Set.of(),
                        Set.of(),
                        failure.operationSource());
                return new QueryExecution(state, attemptSequence, Optional.empty(), Optional.empty());
            }
            if (!resolution.revisions().equals(state.currentAttempt().revisionVector())) {
                try {
                    resultContext = contextIssuer.reissue(
                            state.runId(), state.currentAttempt(), resolution.revisions());
                } catch (CapabilityExecutionContractException exception) {
                    return integrationContractFailure(request, state, attemptSequence, exception);
                }
            }
        }
        ContextIssuer.CapabilityIssue issued;
        try {
            issued = contextIssuer.issueCapabilityResult(
                    state.runId(), resultContext, succeeded,
                    catalogRepositoryIds);
        } catch (CapabilityExecutionContractException exception) {
            return integrationContractFailure(request, state, attemptSequence, exception);
        }
        state = transitions.commitContext(state, issued.context());
        for (AgentObservation observation : issued.observations()) {
            state = transitions.apply(state, new AgentEvent.ObservationRecorded(
                    state.runId(),
                    state.currentAttempt().attemptId(),
                    state.stateRevision(),
                    observation));
        }
        return new QueryExecution(state, attemptSequence, Optional.empty(), Optional.empty());
    }

    private SuccessfulCapabilityResultValidation validateSuccessfulCapabilityResult(
            CapabilityInvocation invocation,
            CapabilityExecutionResult.Succeeded result) {
        Set<RepositoryId> selectedRepositories = new LinkedHashSet<>();
        for (IssuedCandidate selected : invocation.candidates()) {
            selectedRepositories.add(selected.candidate().repositoryId());
        }
        Map<RepositoryId, RepositoryRevision> declaredRevisions = declaredResultRevisions(result);
        if (!selectedRepositories.isEmpty()) {
            validateSelectedResultRepositories(result, selectedRepositories);
        }
        for (Map.Entry<RepositoryId, RepositoryRevision> entry : declaredRevisions.entrySet()) {
            Optional<RepositoryRevision> expected = invocation.expectedRevisions().revisionOf(entry.getKey());
            if (expected.isPresent() && !expected.orElseThrow().equals(entry.getValue())) {
                return new SuccessfulCapabilityResultValidation(
                        declaredRevisions,
                        Optional.of("capability result revision conflicts with the pinned repository revision"));
            }
        }
        return new SuccessfulCapabilityResultValidation(declaredRevisions, Optional.empty());
    }

    private void validateSelectedResultRepositories(
            CapabilityExecutionResult.Succeeded result,
            Set<RepositoryId> selectedRepositories) {
        for (AnalysisCandidate candidate : result.discoveredCandidates()) {
            if (!selectedRepositories.contains(candidate.repositoryId())) {
                throw new CapabilityExecutionContractException(
                        "capability result candidate repository is outside the selected repository scope");
            }
        }
        for (EvidenceRef evidence : result.evidence()) {
            if (!selectedRepositories.contains(evidence.repositoryId())) {
                throw new CapabilityExecutionContractException(
                        "capability result evidence repository is outside the selected repository scope");
            }
        }
    }

    private Map<RepositoryId, RepositoryRevision> declaredResultRevisions(
            CapabilityExecutionResult.Succeeded result) {
        Map<RepositoryId, RepositoryRevision> declared = new LinkedHashMap<>();
        for (AnalysisCandidate candidate : result.discoveredCandidates()) {
            Optional<RepositoryRevision> revision = candidate.repositoryRevision();
            if (revision.isPresent()) {
                registerDeclaredRevision(declared, candidate.repositoryId(), revision.orElseThrow());
            }
        }
        for (EvidenceRef evidence : result.evidence()) {
            registerDeclaredRevision(declared, evidence.repositoryId(), evidence.repositoryRevision());
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(declared));
    }

    private void registerDeclaredRevision(
            Map<RepositoryId, RepositoryRevision> declared,
            RepositoryId repositoryId,
            RepositoryRevision revision) {
        RepositoryRevision previous = declared.putIfAbsent(repositoryId, revision);
        if (Objects.nonNull(previous) && !previous.equals(revision)) {
            throw new CapabilityExecutionContractException(
                    "capability result declares conflicting revisions for the same repository");
        }
    }

    private PostResultRevisionResolution resolveUnscopedResultRevisions(
            AgentRunState state,
            RevisionVector current,
            Map<RepositoryId, RepositoryRevision> declaredRevisions) {
        RevisionVector resolved = current;
        Optional<RepositoryRevisionFailure> failure = Optional.empty();
        boolean revisionConflict = false;
        for (Map.Entry<RepositoryId, RepositoryRevision> declared : declaredRevisions.entrySet()) {
            if (current.revisionOf(declared.getKey()).isPresent()) {
                continue;
            }
            RepositoryRevisionResult result = telemetry.resolveRevision(state, declared.getKey());
            if (result instanceof RepositoryRevisionResult.Failed failed) {
                if (failure.isEmpty()) {
                    failure = Optional.of(failed.failure());
                }
                continue;
            }
            RepositoryRevision actual = ((RepositoryRevisionResult.Ready) result).revision();
            if (!actual.equals(declared.getValue())) {
                revisionConflict = true;
                continue;
            }
            resolved = resolved.pin(declared.getKey(), actual);
        }
        Optional<String> conflict = revisionConflict
                ? Optional.of("capability result revision conflicts with the current repository revision")
                : Optional.empty();
        return new PostResultRevisionResolution(resolved, failure, conflict);
    }

    private QueryExecution integrationContractFailure(
            AgentLoopRequest request,
            AgentRunState state,
            int attemptSequence,
            RuntimeException exception) {
        AgentLoopResult result = concludeIntegrationFailure(state, request, exception);
        return new QueryExecution(state, attemptSequence, Optional.empty(), Optional.of(result));
    }

    private TerminalExecution executeAnswer(
            AgentLoopRequest request,
            SessionHistory sessionHistory,
            AgentRunState currentState,
            AnswerAction action) {
        AgentRunState state = currentState;
        if (cancellationPort.isCancellationRequested(request.runId())) {
            AgentLoopResult result = conclude(
                    state, request, RunOutcome.CANCELLED, CANCELLED_RESPONSE, Optional.empty());
            return new TerminalExecution(state, Optional.empty(), Optional.of(result));
        }
        HandleBinding binding = transitions.currentBinding(state);
        AnswerDocumentValidation documentValidation = documentValidator.validate(
                action.document(),
                state.currentAttempt().issuedEvidence(),
                state.currentAttempt().observations(),
                binding);
        PendingAnswerVerification pendingVerification = new PendingAnswerVerification(
                state.currentAttempt().attemptId(), state.currentAttempt().revisionVector(), action.document(),
                answerVerificationMode);
        state = transitions.apply(state, new AgentEvent.AnswerProposed(state.runId(), state.currentAttempt().attemptId(),
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
            state = transitions.abandonAnswerVerification(state, AnswerVerificationAbandonReason.CANCELLED);
            AgentLoopResult result = conclude(
                    state, request, RunOutcome.CANCELLED, CANCELLED_RESPONSE, Optional.empty());
            return new TerminalExecution(state, Optional.empty(), Optional.of(result));
        }
        HandleBinding binding = transitions.currentBinding(state);
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
        try {
            verificationResult = telemetry.verifyAnswer(state, pending.verificationMode(), verificationContext);
        } catch (ExternalExecutionDeferredException exception) {
            throw deferredExecution(exception);
        } catch (AnswerVerificationUnavailableException exception) {
            throw new AnswerExecutionUnavailableException("answer verification is unavailable", exception);
        } catch (AnswerVerificationContractException exception) {
            return integrationContractTerminalFailure(state, request, exception);
        }
        if (cancellationPort.isCancellationRequested(request.runId())) {
            state = transitions.abandonAnswerVerification(state, AnswerVerificationAbandonReason.CANCELLED);
            AgentLoopResult result = conclude(
                    state, request, RunOutcome.CANCELLED, CANCELLED_RESPONSE, Optional.empty());
            return new TerminalExecution(state, Optional.empty(), Optional.of(result));
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
                state = transitions.apply(state, new AgentEvent.AnswerRejected(
                        state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), verdict));
                state = recordAnswerRejectionObservations(state, pending.document(), verdict);
                return new TerminalExecution(state, Optional.of(rejection), Optional.empty());
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
                request.runId(), request.participant(), request.question(), rendered, ConversationTurnType.ANSWER);
        try {
            state = transitions.applyTerminalAcceptance(state, new AgentEvent.AnswerAccepted(
                    state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), pending.document(),
                    acceptance, request.sessionId(), turn));
        } catch (TerminalAcceptanceCancelledException exception) {
            AgentRunState abandoned = transitions.abandonAnswerVerification(
                    currentState, AnswerVerificationAbandonReason.CANCELLED);
            AgentLoopResult result = conclude(
                    abandoned, request, RunOutcome.CANCELLED, CANCELLED_RESPONSE, Optional.empty());
            return new TerminalExecution(abandoned, Optional.empty(), Optional.of(result));
        }
        sessionPort.append(request.sessionId(), turn);
        AgentLoopResult result = conclude(
                state, request, acceptance.expectedOutcome(), rendered, Optional.of(pending.document()));
        return new TerminalExecution(state, Optional.empty(), Optional.of(result));
    }

    private TerminalExecution integrationContractTerminalFailure(
            AgentRunState state,
            AgentLoopRequest request,
            RuntimeException exception) {
        AgentLoopResult result = concludeIntegrationFailure(state, request, exception);
        return new TerminalExecution(state, Optional.empty(), Optional.of(result));
    }

    private AgentLoopResult concludeIntegrationFailure(
            AgentRunState state,
            AgentLoopRequest request,
            RuntimeException exception) {
        return concludeIntegrationFailure(state, request, exception, Optional.empty());
    }

    private AgentLoopResult concludeIntegrationFailure(
            AgentRunState state,
            AgentLoopRequest request,
            RuntimeException exception,
            Optional<RunFailureReason> failureReason) {
        try {
            AgentRunState abandoned = state.pendingAnswerVerification().isPresent()
                    ? transitions.abandonAnswerVerification(state, AnswerVerificationAbandonReason.INTEGRATION_CONTRACT_FAILURE)
                    : state;
            return conclude(abandoned, request, RunOutcome.FAILED, FAILED_RESPONSE, Optional.empty(), failureReason);
        } catch (AgentTransitionConflictException | AgentLoopException | AgentRunInProgressException commitFailure) {
            AnswerExecutionContractException contractFailure = new AnswerExecutionContractException(
                    "integration contract failure could not be concluded", commitFailure);
            contractFailure.addSuppressed(exception);
            throw contractFailure;
        }
    }

    private AnalysisExecutionDeferredException deferredExecution(ExternalExecutionDeferredException exception) {
        ExecutionDeferral deferral = exception.deferral();
        return new AnalysisExecutionDeferredException(deferral);
    }

    private AgentLoopResult acceptClarification(
            AgentLoopRequest request,
            AgentRunState currentState,
            ClarifyAction clarification) {
        ConversationTurn turn = new ConversationTurn(
                request.runId(), request.participant(), request.question(), clarification.question(),
                ConversationTurnType.CLARIFICATION);
        AgentRunState state;
        try {
            state = transitions.applyTerminalAcceptance(currentState, new AgentEvent.ClarificationAccepted(
                    currentState.runId(), currentState.currentAttempt().attemptId(), currentState.stateRevision(),
                    clarification, request.sessionId(), turn));
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
            AgentRunState state,
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
            RepositoryRevisionResult result = telemetry.resolveRevision(state, repositoryId);
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
            String description) {
        return transitions.apply(state, new AgentEvent.ActionRejected(
                state.runId(),
                state.currentAttempt().attemptId(),
                state.stateRevision(),
                action,
                description));
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
                AgentRunState currentState = state;
                state = transitions.recordRuntimeObservation(
                        state,
                        ObservationCode.UNSUPPORTED_CLAIM,
                        statementVerdict.description(),
                        Set.of(),
                        statement.citations().stream()
                                .map(reference -> findIssuedEvidence(currentState.currentAttempt(), reference.value()).handle())
                                .collect(java.util.stream.Collectors.toSet()),
                        "answer-verifier");
            }
        }
        for (String unaddressedPart : verdict.unaddressedParts()) {
            state = transitions.recordRuntimeObservation(
                    state, ObservationCode.UNADDRESSED_PART, unaddressedPart,
                    Set.of(), Set.of(), "answer-verifier");
        }
        for (String blockingUncertainty : verdict.blockingUncertainties()) {
            state = transitions.recordRuntimeObservation(
                    state, ObservationCode.BLOCKING_UNCERTAINTY, blockingUncertainty,
                    Set.of(), Set.of(), "answer-verifier");
        }
        for (String rejectionReason : verdict.rejectionReasons()) {
            state = transitions.recordRuntimeObservation(
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
        return conclude(state, request, outcome, responseText, document, Optional.empty());
    }

    private AgentLoopResult conclude(
            AgentRunState state,
            AgentLoopRequest request,
            RunOutcome outcome,
            String responseText,
            Optional<AnswerDocument> document,
            Optional<RunFailureReason> failureReason) {
        AgentRunState concluded = transitions.apply(state, new AgentEvent.RunConcluded(
                state.runId(),
                state.currentAttempt().attemptId(),
                state.stateRevision(),
                outcome,
                Optional.empty(),
                failureReason));
        return loopResult(concluded, responseText, document);
    }

    private AgentLoopResult concludeRuntimeNotice(
            AgentRunState state,
            AgentLoopRequest request,
            RuntimeNoticeReason reason) {
        AgentRunState concluded = transitions.apply(state, new AgentEvent.RunConcluded(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(),
                RunOutcome.INCONCLUSIVE, Optional.of(reason), Optional.empty()));
        return loopResult(concluded, runtimeNoticeResponse(Optional.of(reason), RunOutcome.INCONCLUSIVE), Optional.empty());
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

    private AgentPromptContext prompt(
            AgentLoopRequest request,
            SessionHistory sessionHistory,
            AgentRunState state,
            Optional<String> latestRejection) {
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
                state.budget());
    }

    private AgentValidationContext validationContext(AgentRunState state) {
        return new AgentValidationContext(
                state.currentAttempt().issuedCapabilities(),
                state.currentAttempt().issuedCandidates(),
                state.currentAttempt().issuedEvidence(),
                state.currentAttempt().observations(),
                transitions.currentBinding(state),
                state.budget());
    }

    private CapabilityPolicy findCapability(
            RunAttempt attempt,
            QueryAction action) {
        return attempt.issuedCapabilities().entrySet().stream()
                .filter(entry -> entry.getKey().value().equals(action.capability().value()))
                .map(mapEntry -> mapEntry.getValue())
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

    private IssuedEvidence findIssuedEvidence(RunAttempt attempt, String handleValue) {
        return attempt.issuedEvidence().values().stream()
                .filter(evidence -> evidence.handle().value().equals(handleValue))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("accepted evidence is absent after context reissue"));
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

    private Optional<RuntimeNoticeReason> exhaustedBudgetReason(AttemptBudget budget) {
        if (!budget.hasAgentStepRemaining()) {
            return Optional.of(RuntimeNoticeReason.AGENT_STEP_BUDGET_EXHAUSTED);
        }
        if (!budget.hasQueryExecutionRemaining()) {
            return Optional.of(RuntimeNoticeReason.QUERY_EXECUTION_BUDGET_EXHAUSTED);
        }
        if (!budget.hasActionRejectionRemaining()) {
            return Optional.of(RuntimeNoticeReason.ACTION_REJECTION_BUDGET_EXHAUSTED);
        }
        return Optional.empty();
    }

    private record RevisionResolution(
            RevisionVector revisions,
            boolean drifted,
            Optional<RepositoryRevisionFailure> failure) {
    }

    private record SuccessfulCapabilityResultValidation(
            Map<RepositoryId, RepositoryRevision> declaredRevisions,
            Optional<String> revisionConflict) {
    }

    private record PostResultRevisionResolution(
            RevisionVector revisions,
            Optional<RepositoryRevisionFailure> failure,
            Optional<String> revisionConflict) {
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
            List<CapabilityPolicy> capabilityCatalog,
            List<RepositoryDescriptor> repositoryCatalog,
            Set<RepositoryId> catalogRepositoryIds) {
    }

    private record ActiveExecution(
            AgentRunState state,
            SessionHistory sessionHistory,
            List<CapabilityPolicy> capabilityCatalog,
            List<RepositoryDescriptor> repositoryCatalog,
            Set<RepositoryId> catalogRepositoryIds,
            int attemptSequence,
            Optional<String> latestRejection) {
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
            Optional<String> latestRejection,
            Optional<AgentLoopResult> result) {
    }

    private record TerminalExecution(
            AgentRunState state,
            Optional<String> latestRejection,
            Optional<AgentLoopResult> result) {
    }
}
