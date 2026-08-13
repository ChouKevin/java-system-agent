package com.java.system.agent.answering.application.loop;

import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.candidate.AnalysisCandidate;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.evidence.EvidenceRef;
import com.java.system.agent.answering.domain.observation.AgentObservation;
import com.java.system.agent.answering.domain.observation.ObservationCode;
import com.java.system.agent.answering.domain.run.AgentEvent;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.ActionResult;
import com.java.system.agent.answering.domain.run.RunAttempt;
import com.java.system.agent.answering.domain.run.RunFailureReason;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.in.AnswerExecutionContractException;
import com.java.system.agent.answering.port.in.AnswerExecutionContractFailure;
import com.java.system.agent.answering.port.out.AnalysisCancellationPort;
import com.java.system.agent.answering.port.out.CapabilityExecutionContractException;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.answering.port.out.CapabilityInvocation;

import java.util.Collections;
import java.util.LinkedHashMap;
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
    private final ContextIssuer contextIssuer;
    private final AgentRunTransitions transitions;
    private final TerminalResponseCoordinator terminalResponseCoordinator;

    QueryActionExecutor(
            AgentLoopTelemetry telemetry,
            AnalysisCancellationPort cancellationPort,
            ContextIssuer contextIssuer,
            AgentRunTransitions transitions,
            TerminalResponseCoordinator terminalResponseCoordinator) {
        this.telemetry = Objects.requireNonNull(telemetry, "agent loop telemetry must not be null");
        this.cancellationPort = Objects.requireNonNull(cancellationPort, "analysis cancellation port must not be null");
        this.contextIssuer = Objects.requireNonNull(contextIssuer, "context issuer must not be null");
        this.transitions = Objects.requireNonNull(transitions, "agent run transitions must not be null");
        this.terminalResponseCoordinator = Objects.requireNonNull(
                terminalResponseCoordinator, "terminal response coordinator must not be null");
    }

    ActionLaneOutcome execute(
            AgentLoopRequest request,
            AgentRunState currentState,
            QueryAction action,
            int attemptSequence,
            Set<RepositoryId> catalogRepositoryIds) {
        AgentRunState state = currentState;
        try {
            validateRuntimeScope(state);
        } catch (CapabilityExecutionContractException exception) {
            return integrationContractFailure(state, exception, attemptSequence);
        }
        state = transitions.apply(state, new AgentEvent.ActionAccepted(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), action));
        state = transitions.apply(state, new AgentEvent.QueryBudgetConsumed(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision()));
        if (cancellationPort.isCancellationRequested(request.runId())) {
            return new ActionLaneOutcome.Terminal(terminalResponseCoordinator.conclude(
                    state, RunOutcome.CANCELLED, TerminalResponseCoordinator.CANCELLED_RESPONSE,
                    Optional.empty(), Optional.empty()));
        }
        RunAttempt queryContext = state.currentAttempt();
        CapabilityInvocation capabilityInvocation = new CapabilityInvocation(
                findCapability(queryContext, action),
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
        RepositoryId scopedRepository = requiredScopedRepository(invocation.expectedRevisions());
        Map<RepositoryId, RepositoryRevision> declaredRevisions = declaredResultRevisions(result);
        validateRuntimeScopedResultRepositories(result, Set.of(scopedRepository));
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

    private void validateRuntimeScope(AgentRunState state) {
        RepositoryId expectedRepository = state.requestIdentity().repositoryId();
        RevisionVector revisions = state.currentAttempt().revisionVector();
        if (revisions.entries().size() != 1 || !revisions.repositoryIds().getFirst().equals(expectedRepository)) {
            throw new CapabilityExecutionContractException(
                    "QUERY execution requires exactly one pinned revision for the request repository");
        }
    }

    private RepositoryId requiredScopedRepository(RevisionVector revisions) {
        if (revisions.entries().size() != 1) {
            throw new CapabilityExecutionContractException("QUERY execution requires exactly one pinned repository revision");
        }
        return revisions.repositoryIds().getFirst();
    }

    private void validateRuntimeScopedResultRepositories(
            CapabilityExecutionResult.Succeeded result,
            Set<RepositoryId> runtimeScopedRepositories) {
        for (AnalysisCandidate candidate : result.discoveredCandidates()) {
            if (!runtimeScopedRepositories.contains(candidate.repositoryId())) {
                throw new CapabilityExecutionContractException(
                        "capability result candidate repository is outside the runtime repository scope");
            }
        }
        for (EvidenceRef evidence : result.evidence()) {
            if (!runtimeScopedRepositories.contains(evidence.repositoryId())) {
                throw new CapabilityExecutionContractException(
                        "capability result evidence repository is outside the runtime repository scope");
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

    private CapabilityPolicy findCapability(RunAttempt attempt, QueryAction action) {
        return attempt.issuedCapabilities().entrySet().stream()
                .filter(entry -> entry.getKey().value().equals(action.capability().value()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("accepted capability is absent after context reissue"));
    }

    private record SuccessfulCapabilityResultValidation(
            Map<RepositoryId, RepositoryRevision> declaredRevisions,
            Optional<String> revisionConflict) {
    }

}
