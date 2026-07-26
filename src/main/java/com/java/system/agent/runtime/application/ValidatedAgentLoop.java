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
import com.java.system.agent.runtime.domain.answer.StatementId;
import com.java.system.agent.runtime.domain.answer.StatementVerdict;
import com.java.system.agent.runtime.domain.answer.StatementVerdictStatus;
import com.java.system.agent.runtime.domain.candidate.IssuedCandidate;
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
import com.java.system.agent.runtime.domain.run.PendingTerminalResponse;
import com.java.system.agent.runtime.domain.run.RunRequestIdentity;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import com.java.system.agent.runtime.domain.capability.CapabilityDescriptor;
import com.java.system.agent.runtime.port.out.AgentActionPort;
import com.java.system.agent.runtime.port.out.AgentActionProposal;
import com.java.system.agent.runtime.port.out.AgentPromptContext;
import com.java.system.agent.runtime.port.out.AgentSemanticQuery;
import com.java.system.agent.runtime.port.out.AgentSemanticQueryPort;
import com.java.system.agent.runtime.port.out.AgentSemanticQueryResult;
import com.java.system.agent.runtime.port.out.AnalysisAttemptIdGenerator;
import com.java.system.agent.runtime.port.out.AnalysisCancellationPort;
import com.java.system.agent.runtime.port.out.AnswerVerificationContext;
import com.java.system.agent.runtime.port.out.AnswerVerificationPort;
import com.java.system.agent.runtime.port.out.CapabilityCatalogPort;
import com.java.system.agent.runtime.port.out.SessionPort;
import com.java.system.agent.runtime.port.out.RepositoryCatalogPort;
import com.java.system.agent.runtime.port.out.RepositoryDescriptor;
import com.java.system.agent.runtime.port.out.RepositoryRevisionPort;
import com.java.system.agent.runtime.port.out.RepositoryRevisionResult;
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
import java.util.stream.Collectors;

/**
 * 唯一推進 bounded action、semantic query、verification 與 terminal persistence 的 orchestrator
 */
public final class ValidatedAgentLoop {

    public static final String INCONCLUSIVE_RESPONSE = "目前資訊不足以產生可驗證的回答";
    public static final String FAILED_RESPONSE = "分析流程發生錯誤，未回傳未驗證內容";
    public static final String CANCELLED_RESPONSE = "分析已取消";

    private final AgentActionPort actionPort;
    private final AgentSemanticQueryPort semanticQueryPort;
    private final AnswerVerificationPort verificationPort;
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
            AgentSemanticQueryPort semanticQueryPort,
            AnswerVerificationPort verificationPort,
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
        this.semanticQueryPort = Objects.requireNonNull(
                semanticQueryPort, "agent semantic query port must not be null");
        this.verificationPort = Objects.requireNonNull(
                verificationPort, "answer verification port must not be null");
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
        ActiveExecution execution;
        if (persisted.isPresent()) {
            AgentRunState persistedState = persisted.orElseThrow();
            if (persistedState.pendingTerminalResponse().isPresent()
                    || persistedState.status() == AgentRunStatus.CONCLUDED) {
                return resumePersistedState(request, persistedState);
            }
            validateRequestIdentity(request, persistedState);
            if (request.executionMode() == AnswerExecutionMode.INITIAL) {
                throw new AgentRunInProgressException("agent run is already in progress: " + request.runId().value());
            }
            if (request.executionMode() == AnswerExecutionMode.TERMINAL_RECONCILIATION) {
                throw new AgentRunTerminalReconciliationException(
                        "terminal reconciliation requires a terminal agent run: " + request.runId().value());
            }
            try {
                execution = prepareRetryExecution(request, persistedState);
            } catch (AgentTransitionConflictException exception) {
                return resolveRetryConflict(request, exception);
            }
        } else {
            if (request.executionMode() == AnswerExecutionMode.TERMINAL_RECONCILIATION) {
                throw new AgentRunTerminalReconciliationException(
                        "terminal reconciliation requires a persisted terminal agent run: "
                                + request.runId().value());
            }
            int attemptSequence = request.executionAttempt();
            BootstrapPreparation preparation = prepareBootstrap(request, attemptSequence);
            InitialClaim initialClaim = claimInitialRun(request, preparation.initialState(), preparation.initialContext());
            if (initialClaim.result().isPresent()) {
                return initialClaim.result().orElseThrow();
            }
            execution = new ActiveExecution(
                    initialClaim.state().orElseThrow(),
                    preparation.sessionHistory(),
                    preparation.capabilityCatalog(),
                    preparation.repositoryCatalog(),
                    preparation.catalogRepositoryIds(),
                    attemptSequence);
        }
        AgentRunState state = execution.state();
        SessionHistory sessionHistory = execution.sessionHistory();
        List<CapabilityDescriptor> capabilityCatalog = execution.capabilityCatalog();
        List<RepositoryDescriptor> repositoryCatalog = execution.repositoryCatalog();
        Set<RepositoryId> catalogRepositoryIds = execution.catalogRepositoryIds();
        int attemptSequence = execution.attemptSequence();

        Optional<String> latestRejection = Optional.empty();
        boolean forcedFinalResponse = false;
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
                        request, state, answerAction, finalResponseMode);
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
            return new InitialClaim(Optional.of(bootstrap(initialState, initialContext)), Optional.empty());
        } catch (AgentTransitionConflictException exception) {
            AgentRunState authoritative = transitionCommitter.findByRunId(request.runId())
                    .orElseThrow(() -> exception);
            if (authoritative.pendingTerminalResponse().isPresent()
                    || authoritative.status() == AgentRunStatus.CONCLUDED) {
                return new InitialClaim(Optional.empty(), Optional.of(resumePersistedState(request, authoritative)));
            }
            throw new AgentRunInProgressException("agent run is already in progress: " + request.runId().value());
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
        RunAttempt restartedContext = contextIssuer.issueInitial(
                request.runId(),
                restarted.currentAttempt().attemptId(),
                RevisionVector.empty(),
                capabilityCatalog,
                repositoryCatalog);
        AgentRunState contextualized = commitContext(restarted, restartedContext);
        return new ActiveExecution(
                contextualized,
                sessionHistory,
                capabilityCatalog,
                repositoryCatalog,
                catalogRepositoryIds,
                contextualized.attemptSequence());
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

    private AgentLoopResult resolveRetryConflict(AgentLoopRequest request, AgentTransitionConflictException exception) {
        AgentRunState authoritative = transitionCommitter.findByRunId(request.runId())
                .orElseThrow(() -> exception);
        validateRequestIdentity(request, authoritative);
        if (authoritative.pendingTerminalResponse().isPresent()
                || authoritative.status() == AgentRunStatus.CONCLUDED) {
            return resumePersistedState(request, authoritative);
        }
        throw new AgentRunInProgressException("agent run is already in progress: " + request.runId().value());
    }

    private AgentLoopResult resumePersistedState(AgentLoopRequest request, AgentRunState persisted) {
        validateRequestIdentity(request, persisted);
        if (persisted.status() == AgentRunStatus.CONCLUDED) {
            return concludedResult(persisted);
        }
        if (persisted.status() == AgentRunStatus.RUNNING && persisted.pendingTerminalResponse().isPresent()) {
            PendingTerminalResponse pending = persisted.pendingTerminalResponse().orElseThrow();
            sessionPort.append(pending.sessionId(), pending.turn());
            return concludePersistedTerminal(persisted, request, pending);
        }
        throw new IllegalStateException("persisted agent run cannot be safely resumed without a terminal response");
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
            return new AgentLoopResult(
                    state.runId(), outcome, pending.turn().assistantMessage(), pendingAnswerDocument(pending),
                    state.currentAttempt().revisionVector());
        }
        return new AgentLoopResult(
                state.runId(), outcome, runtimeFixedResponse(outcome), Optional.empty(),
                state.currentAttempt().revisionVector());
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
        RevisionResolution revisionResolution = resolveRevisions(state.currentAttempt().revisionVector(), selectedValues);
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
            RunAttempt restartedContext = contextIssuer.issueInitial(
                    state.runId(),
                    nextAttemptId,
                    RevisionVector.empty(),
                    capabilityCatalog,
                    repositoryCatalog);
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
        state = commit(state, new AgentEvent.ActionAccepted(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), action));
        state = commit(state, new AgentEvent.QueryBudgetConsumed(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision()));
        if (!revisionResolution.revisions().equals(state.currentAttempt().revisionVector())) {
            RunAttempt rebound = contextIssuer.reissue(
                    state.runId(), state.currentAttempt(), revisionResolution.revisions());
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
        AgentSemanticQuery semanticQuery = new AgentSemanticQuery(
                findCapability(queryContext, action),
                reboundSelection,
                action.questionToResolve(),
                action.arguments(),
                queryContext.revisionVector());
        AgentSemanticQueryResult result = Objects.requireNonNull(
                semanticQueryPort.query(semanticQuery),
                "agent semantic query port must return a result");
        ContextIssuer.SemanticIssue issued = contextIssuer.issueSemanticResult(
                state.runId(), state.currentAttempt(), result, catalogRepositoryIds);
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

    private TerminalExecution executeAnswer(
            AgentLoopRequest request,
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
        AnswerVerificationContext verificationContext = new AnswerVerificationContext(
                request.question(),
                action.document(),
                List.copyOf(documentValidation.citedEvidence().values()),
                List.copyOf(documentValidation.referencedObservations().values()));
        AnswerVerdict verdict = Objects.requireNonNull(
                verificationPort.verify(verificationContext),
                "answer verification port must return a verdict");
        verdictValidator.validate(documentValidation, verdict);
        if (cancellationPort.isCancellationRequested(request.runId())) {
            AgentLoopResult result = conclude(
                    state, request, RunOutcome.CANCELLED, CANCELLED_RESPONSE, Optional.empty());
            return new TerminalExecution(state, Optional.empty(), false, Optional.of(result));
        }
        if (verdict.disposition() == AnswerDisposition.REJECTED) {
            String rejection = rejectionDescription(verdict);
            state = reject(state, Optional.of(action), rejection, finalResponseMode);
            state = recordAnswerRejectionObservations(state, action.document(), verdict);
            if (finalResponseMode) {
                AgentLoopResult result = conclude(
                        state, request, RunOutcome.INCONCLUSIVE, INCONCLUSIVE_RESPONSE, Optional.empty());
                return new TerminalExecution(state, Optional.empty(), true, Optional.of(result));
            }
            return new TerminalExecution(state, Optional.of(rejection), false, Optional.empty());
        }
        RunOutcome outcome = verdict.disposition() == AnswerDisposition.ACCEPTED_COMPLETE
                ? RunOutcome.COMPLETED
                : RunOutcome.INCONCLUSIVE;
        String rendered = action.document().renderParagraphs();
        ConversationTurn turn = new ConversationTurn(
                request.runId(), request.question(), rendered, ConversationTurnType.ANSWER);
        try {
            state = commitTerminalAcceptance(state, new AgentEvent.AnswerAccepted(
                    state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), action.document(), verdict,
                    request.sessionId(), turn, finalResponseMode));
        } catch (TerminalAcceptanceCancelledException exception) {
            AgentLoopResult result = conclude(
                    currentState, request, RunOutcome.CANCELLED, CANCELLED_RESPONSE, Optional.empty());
            return new TerminalExecution(currentState, Optional.empty(), false, Optional.of(result));
        }
        sessionPort.append(request.sessionId(), turn);
        AgentLoopResult result = conclude(
                state, request, outcome, rendered, Optional.of(action.document()));
        return new TerminalExecution(state, Optional.empty(), false, Optional.of(result));
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
        for (RepositoryId repositoryId : selectedRepositories) {
            RepositoryRevisionResult result = Objects.requireNonNull(
                    repositoryRevisionPort.currentRevision(repositoryId),
                    "repository revision port must return a result");
            RepositoryRevision revision = result.revision()
                    .orElseThrow(() -> new IllegalStateException(
                            "repository revision is unavailable: "
                                    + result.failure().orElseThrow().message()));
            Optional<RepositoryRevision> pinned = current.revisionOf(repositoryId);
            if (pinned.isPresent() && !pinned.orElseThrow().equals(revision)) {
                drifted = true;
            } else if (pinned.isEmpty()) {
                revisions = revisions.pin(repositoryId, revision);
            }
        }
        return new RevisionResolution(revisions, drifted);
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
        return new AgentLoopResult(
                request.runId(),
                outcome,
                responseText,
                document,
                concluded.currentAttempt().revisionVector());
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
                || !budget.hasSemanticQueryRemaining()
                || !budget.hasActionRejectionRemaining();
    }

    private record RevisionResolution(RevisionVector revisions, boolean drifted) {
    }

    private record InitialClaim(Optional<AgentRunState> state, Optional<AgentLoopResult> result) {
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
            int attemptSequence) {
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
