package com.java.system.agent.answering.application.loop;

import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.candidate.AnalysisCandidate;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.evidence.EvidenceRef;
import com.java.system.agent.answering.domain.observation.AgentObservation;
import com.java.system.agent.answering.domain.observation.ObservationCode;
import com.java.system.agent.answering.domain.run.AgentEvent;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.ActionResult;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.RunAttempt;
import com.java.system.agent.answering.domain.run.RunFailureReason;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.in.AnswerExecutionContractException;
import com.java.system.agent.answering.port.in.AnswerExecutionContractFailure;
import com.java.system.agent.answering.port.out.AnalysisAttemptIdGenerator;
import com.java.system.agent.answering.port.out.AnalysisCancellationPort;
import com.java.system.agent.answering.port.out.CapabilityExecutionContractException;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.answering.port.out.CapabilityInvocation;
import com.java.system.agent.answering.port.out.RepositoryDescriptor;
import com.java.system.agent.answering.port.out.RepositoryRevisionContractException;
import com.java.system.agent.answering.port.out.RepositoryRevisionFailure;
import com.java.system.agent.answering.port.out.RepositoryRevisionResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 執行已驗證 QUERY action 並回傳 action lane 結果的 application 元件
 */
final class QueryActionExecutor {

    private final AgentLoopTelemetry telemetry;
    private final AnalysisCancellationPort cancellationPort;
    private final AnalysisAttemptIdGenerator attemptIdGenerator;
    private final ContextIssuer contextIssuer;
    private final AgentRunTransitions transitions;
    private final TerminalResponseCoordinator terminalResponseCoordinator;

    QueryActionExecutor(
            AgentLoopTelemetry telemetry,
            AnalysisCancellationPort cancellationPort,
            AnalysisAttemptIdGenerator attemptIdGenerator,
            ContextIssuer contextIssuer,
            AgentRunTransitions transitions,
            TerminalResponseCoordinator terminalResponseCoordinator) {
        this.telemetry = Objects.requireNonNull(telemetry, "agent loop telemetry must not be null");
        this.cancellationPort = Objects.requireNonNull(cancellationPort, "analysis cancellation port must not be null");
        this.attemptIdGenerator = Objects.requireNonNull(
                attemptIdGenerator, "analysis attempt ID generator must not be null");
        this.contextIssuer = Objects.requireNonNull(contextIssuer, "context issuer must not be null");
        this.transitions = Objects.requireNonNull(transitions, "agent run transitions must not be null");
        this.terminalResponseCoordinator = Objects.requireNonNull(
                terminalResponseCoordinator, "terminal response coordinator must not be null");
    }

    ActionLaneOutcome execute(
            AgentLoopRequest request,
            AgentRunState currentState,
            QueryAction action,
            List<IssuedCandidate> resolvedCandidates,
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
            revisionResolution = resolveRevisions(state, state.currentAttempt().revisionVector(), resolvedCandidates);
        } catch (RepositoryRevisionContractException exception) {
            return integrationContractFailure(state, exception, attemptSequence);
        }
        if (revisionResolution.drifted()) {
            boolean restartAllowed = state.budget().hasRevisionRestartRemaining();
            state = recordQueryResult(state, new ActionResult.QueryInvalidated("selected repository revision changed"));
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
                        state.runId(), nextAttemptId, RevisionVector.empty(), capabilityCatalog, repositoryCatalog);
            } catch (CapabilityExecutionContractException exception) {
                return integrationContractFailure(state, exception, nextAttemptSequence);
            }
            AnalysisAttemptId invalidatedAttemptId = state.currentAttempt().attemptId();
            state = transitions.apply(state, new AgentEvent.AttemptStarted(
                    state.runId(), invalidatedAttemptId, state.stateRevision(), RunAttempt.empty(nextAttemptId)));
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
                return new ActionLaneOutcome.Continue(
                        state, nextAttemptSequence, Optional.of(description + "; restart budget is exhausted"));
            }
            return new ActionLaneOutcome.Continue(
                    state, nextAttemptSequence, Optional.of("repository revision changed; a new attempt was started"));
        }
        if (revisionResolution.failure().isPresent()) {
            RepositoryRevisionFailure failure = revisionResolution.failure().orElseThrow();
            state = transitions.apply(state, new AgentEvent.ActionAccepted(
                    state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), action));
            state = transitions.apply(state, new AgentEvent.QueryBudgetConsumed(
                    state.runId(), state.currentAttempt().attemptId(), state.stateRevision()));
            state = transitions.recordRuntimeObservation(state, ObservationCode.EXECUTION_FAILED,
                    failure.description(), Set.of(), Set.of(), failure.operationSource());
            state = recordQueryResult(state, new ActionResult.QueryFailed(
                    List.of(lastObservationId(state)), failure.description()));
            return new ActionLaneOutcome.Continue(state, attemptSequence, Optional.empty());
        }
        state = transitions.apply(state, new AgentEvent.ActionAccepted(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), action));
        state = transitions.apply(state, new AgentEvent.QueryBudgetConsumed(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision()));
        if (!revisionResolution.revisions().equals(state.currentAttempt().revisionVector())) {
            RunAttempt rebound;
            try {
                rebound = contextIssuer.reissue(state.runId(), state.currentAttempt(), revisionResolution.revisions());
            } catch (CapabilityExecutionContractException exception) {
                return integrationContractFailure(state, exception, attemptSequence);
            }
            state = transitions.commitContext(state, rebound);
        }
        if (cancellationPort.isCancellationRequested(request.runId())) {
            return new ActionLaneOutcome.Terminal(terminalResponseCoordinator.conclude(
                    state, RunOutcome.CANCELLED, TerminalResponseCoordinator.CANCELLED_RESPONSE,
                    Optional.empty(), Optional.empty()));
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
            terminalResponseCoordinator.concludeIntegrationFailure(
                    state, exception, Optional.of(RunFailureReason.PLANNING_TOOL_CONTRACT));
            throw new AnswerExecutionContractException(
                    AnswerExecutionContractFailure.PLANNING_TOOL_CONTRACT,
                    "planning tool contract failed", exception);
        }
        if (result instanceof CapabilityExecutionResult.Failed failed) {
            state = transitions.recordCapabilityFailureObservation(state, ObservationCode.EXECUTION_FAILED,
                    failed.failure().description(), Set.of(), Set.of(), failed.failure().operationSource());
            state = recordQueryResult(state, new ActionResult.QueryFailed(
                    List.of(lastObservationId(state)), failed.failure().description()));
            return new ActionLaneOutcome.Continue(state, attemptSequence, Optional.empty());
        }
        CapabilityExecutionResult.Succeeded succeeded = (CapabilityExecutionResult.Succeeded) result;
        SuccessfulCapabilityResultValidation validation;
        try {
            contextIssuer.validateCapabilityRepositories(succeeded, catalogRepositoryIds);
            validation = validateSuccessfulCapabilityResult(capabilityInvocation, succeeded);
        } catch (CapabilityExecutionContractException exception) {
            return integrationContractFailure(state, exception, attemptSequence);
        }
        if (validation.revisionConflict().isPresent()) {
            state = transitions.recordRuntimeObservation(
                    state,
                    ObservationCode.CONFLICTING_EVIDENCE,
                    validation.revisionConflict().orElseThrow(),
                    Set.of(),
                    Set.of(),
                    "capability-result-validator");
            state = recordQueryResult(state, new ActionResult.QueryFailed(
                    List.of(lastObservationId(state)), validation.revisionConflict().orElseThrow()));
            return new ActionLaneOutcome.Continue(state, attemptSequence, Optional.empty());
        }
        RunAttempt resultContext = state.currentAttempt();
        if (capabilityInvocation.candidates().isEmpty()) {
            PostResultRevisionResolution resolution;
            try {
                resolution = resolveUnscopedResultRevisions(
                        state, state.currentAttempt().revisionVector(), validation.declaredRevisions());
            } catch (RepositoryRevisionContractException exception) {
                return integrationContractFailure(state, exception, attemptSequence);
            }
            if (resolution.revisionConflict().isPresent()) {
                state = transitions.recordRuntimeObservation(
                        state,
                        ObservationCode.CONFLICTING_EVIDENCE,
                        resolution.revisionConflict().orElseThrow(),
                        Set.of(),
                        Set.of(),
                        "capability-result-validator");
                state = recordQueryResult(state, new ActionResult.QueryFailed(
                        List.of(lastObservationId(state)), resolution.revisionConflict().orElseThrow()));
                return new ActionLaneOutcome.Continue(state, attemptSequence, Optional.empty());
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
                state = recordQueryResult(state, new ActionResult.QueryFailed(
                        List.of(lastObservationId(state)), failure.description()));
                return new ActionLaneOutcome.Continue(state, attemptSequence, Optional.empty());
            }
            if (!resolution.revisions().equals(state.currentAttempt().revisionVector())) {
                try {
                    resultContext = contextIssuer.reissue(
                            state.runId(), state.currentAttempt(), resolution.revisions());
                } catch (CapabilityExecutionContractException exception) {
                    return integrationContractFailure(state, exception, attemptSequence);
                }
            }
        }
        ContextIssuer.CapabilityIssue issued;
        try {
            issued = contextIssuer.issueCapabilityResult(state.runId(), resultContext, succeeded, catalogRepositoryIds);
        } catch (CapabilityExecutionContractException exception) {
            return integrationContractFailure(state, exception, attemptSequence);
        }
        state = transitions.commitContext(state, issued.context());
        for (AgentObservation observation : issued.observations()) {
            state = transitions.apply(state, new AgentEvent.ObservationRecorded(
                    state.runId(),
                    state.currentAttempt().attemptId(),
                    state.stateRevision(),
                    observation));
        }
        List<String> observationIds = issued.observations().stream().map(observation -> observation.id().value()).toList();
        state = recordQueryResult(state, new ActionResult.QuerySucceeded(
                issued.resultCandidateHandleValues(), issued.resultEvidenceHandleValues(), observationIds));
        return new ActionLaneOutcome.Continue(state, attemptSequence, Optional.empty());
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

    private ActionLaneOutcome integrationContractFailure(
            AgentRunState state,
            RuntimeException exception,
            int attemptSequence) {
        AgentLoopResult result = terminalResponseCoordinator.concludeIntegrationFailure(state, exception);
        return new ActionLaneOutcome.Terminal(result);
    }

    private AgentRunState recordQueryResult(AgentRunState state, ActionResult result) {
        return transitions.apply(state, new AgentEvent.ActionResultRecorded(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), result));
    }

    private String lastObservationId(AgentRunState state) {
        return state.currentAttempt().observations().values().stream()
                .reduce((first, second) -> second)
                .map(observation -> observation.id().value())
                .orElseThrow(() -> new IllegalStateException("query failure requires a recorded observation"));
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

    private CapabilityPolicy findCapability(RunAttempt attempt, QueryAction action) {
        return attempt.issuedCapabilities().entrySet().stream()
                .filter(entry -> entry.getKey().value().equals(action.capability().value()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("accepted capability is absent after context reissue"));
    }

    private IssuedCandidate findIssuedCandidate(RunAttempt attempt, String handleValue) {
        return attempt.issuedCandidates().values().stream()
                .filter(candidate -> candidate.handle().value().equals(handleValue))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("accepted candidate is absent after context reissue"));
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
}
