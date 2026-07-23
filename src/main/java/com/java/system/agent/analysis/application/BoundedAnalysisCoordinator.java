package com.java.system.agent.analysis.application;

import com.java.system.agent.analysis.domain.AnalysisOutcome;
import com.java.system.agent.analysis.domain.AnalysisState;
import com.java.system.agent.analysis.domain.AttemptOutcome;
import com.java.system.agent.analysis.domain.InformationNeed;
import com.java.system.agent.analysis.domain.InformationNeedId;
import com.java.system.agent.analysis.domain.RepositoryDiscoverySource;
import com.java.system.agent.analysis.domain.RepositoryId;
import com.java.system.agent.analysis.domain.SemanticTarget;
import com.java.system.agent.analysis.domain.SemanticTargetKind;
import com.java.system.agent.analysis.port.in.AnalysisExecutionCommand;
import com.java.system.agent.analysis.port.in.AnalysisExecutionException;
import com.java.system.agent.analysis.port.in.AnalysisExecutionResult;
import com.java.system.agent.analysis.port.in.AnalysisTerminationReason;
import com.java.system.agent.analysis.port.in.ExecuteAnalysisUseCase;
import com.java.system.agent.analysis.port.out.AnalysisCancellationPort;
import com.java.system.agent.analysis.port.out.RepositoryDiscovery;
import com.java.system.agent.analysis.port.out.SemanticFailure;
import com.java.system.agent.analysis.port.out.SemanticFailureCode;
import com.java.system.agent.analysis.port.out.SemanticQuery;
import com.java.system.agent.analysis.port.out.SemanticQueryPort;
import com.java.system.agent.analysis.port.out.SemanticQueryResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Executes one deterministic, revision-bound analysis run using the bounded runtime collaborators.
 */
public final class BoundedAnalysisCoordinator implements ExecuteAnalysisUseCase {

    private final AttemptLifecycleManager attemptLifecycleManager;
    private final GoalEvaluator goalEvaluator;
    private final InformationNeedPlanner informationNeedPlanner;
    private final SemanticCapabilityRegistry semanticCapabilityRegistry;
    private final SemanticRetryPolicy semanticRetryPolicy;
    private final SemanticResultHandler semanticResultHandler;
    private final NoProgressPolicy noProgressPolicy;
    private final TransitionCommitter transitionCommitter;
    private final SemanticQueryPort semanticQueryPort;
    private final AnalysisCancellationPort analysisCancellationPort;

    public BoundedAnalysisCoordinator(
            AttemptLifecycleManager attemptLifecycleManager,
            GoalEvaluator goalEvaluator,
            InformationNeedPlanner informationNeedPlanner,
            SemanticCapabilityRegistry semanticCapabilityRegistry,
            SemanticRetryPolicy semanticRetryPolicy,
            SemanticResultHandler semanticResultHandler,
            NoProgressPolicy noProgressPolicy,
            TransitionCommitter transitionCommitter,
            SemanticQueryPort semanticQueryPort,
            AnalysisCancellationPort analysisCancellationPort) {
        this.attemptLifecycleManager = Objects.requireNonNull(
                attemptLifecycleManager, "attempt lifecycle manager must not be null");
        this.goalEvaluator = Objects.requireNonNull(goalEvaluator, "goal evaluator must not be null");
        this.informationNeedPlanner = Objects.requireNonNull(
                informationNeedPlanner, "information need planner must not be null");
        this.semanticCapabilityRegistry = Objects.requireNonNull(
                semanticCapabilityRegistry, "semantic capability registry must not be null");
        this.semanticRetryPolicy = Objects.requireNonNull(
                semanticRetryPolicy, "semantic retry policy must not be null");
        this.semanticResultHandler = Objects.requireNonNull(
                semanticResultHandler, "semantic result handler must not be null");
        this.noProgressPolicy = Objects.requireNonNull(noProgressPolicy, "no-progress policy must not be null");
        this.transitionCommitter = Objects.requireNonNull(
                transitionCommitter, "transition committer must not be null");
        this.semanticQueryPort = Objects.requireNonNull(semanticQueryPort, "semantic query port must not be null");
        this.analysisCancellationPort = Objects.requireNonNull(
                analysisCancellationPort, "analysis cancellation port must not be null");
    }

    @Override
    public AnalysisExecutionResult execute(AnalysisExecutionCommand command) {
        Objects.requireNonNull(command, "analysis execution command must not be null");
        AttemptLifecycle lifecycle;
        try {
            lifecycle = attemptLifecycleManager.startForExecution(command, analysisCancellationPort);
        } catch (AttemptPreparationException exception) {
            return concludeForPreparationFailure(exception);
        } catch (AttemptPreparationCancelledException exception) {
            return conclude(exception.lastCommittedLifecycle(), AttemptOutcome.CANCELLED, AnalysisOutcome.CANCELLED,
                    AnalysisTerminationReason.CANCELLED);
        } catch (AttemptPreparationBudgetExhaustedException exception) {
            return conclude(exception.lastCommittedLifecycle(), AttemptOutcome.INCONCLUSIVE, AnalysisOutcome.INCONCLUSIVE,
                    AnalysisTerminationReason.BUDGET_EXHAUSTED);
        } catch (AttemptLifecycleExternalFailureException exception) {
            throw executionFailure(exception.lastCommittedLifecycle(), exception);
        }
        return executeActive(command, lifecycle);
    }

    private AnalysisExecutionResult executeActive(
            AnalysisExecutionCommand command,
            AttemptLifecycle lifecycle) {
        List<ProgressFingerprint> progressHistory = new ArrayList<>();
        Map<InformationNeedId, Integer> semanticCallsByNeed = new HashMap<>();

        try {
            while (lifecycle.run().outcome().isEmpty()) {
                if (isCancellationRequested(lifecycle)) {
                    return conclude(lifecycle, AttemptOutcome.CANCELLED, AnalysisOutcome.CANCELLED,
                            AnalysisTerminationReason.CANCELLED);
                }

                GoalEvaluation goalEvaluation = goalEvaluator.evaluate(
                        command.goal(), lifecycle.state(), Optional.empty());
                if (goalEvaluation.status() == GoalEvaluationStatus.TERMINAL) {
                    return concludeForGoal(lifecycle, goalEvaluation);
                }

                InformationNeed informationNeed = lifecycle.state().pendingNeeds().firstEntry().getValue();
                PlanningResult planning = informationNeedPlanner.plan(
                        lifecycle.state(), informationNeed, semanticCapabilityRegistry);
                if (planning.status() != PlanningStatus.PLANNED) {
                    return concludeForPlanning(lifecycle, planning.status());
                }
                PlannedCapability plannedCapability = planning.plannedCapability().orElseThrow();
                if (plannedCapability.semanticTarget().isEmpty()) {
                    return conclude(lifecycle, AttemptOutcome.INCONCLUSIVE, AnalysisOutcome.INCONCLUSIVE,
                            AnalysisTerminationReason.PREREQUISITE_MISSING);
                }
                SemanticQuery query = queryFor(plannedCapability);

                if (isCancellationRequested(lifecycle)) {
                    return conclude(lifecycle, AttemptOutcome.CANCELLED, AnalysisOutcome.CANCELLED,
                            AnalysisTerminationReason.CANCELLED);
                }
                if (hasReachedSemanticCallLimit(semanticCallsByNeed, informationNeed.id())) {
                    return conclude(lifecycle, AttemptOutcome.INCONCLUSIVE, AnalysisOutcome.INCONCLUSIVE,
                            AnalysisTerminationReason.NO_PROGRESS);
                }
                if (!lifecycle.state().budget().hasStepRemaining()) {
                    return conclude(lifecycle, AttemptOutcome.INCONCLUSIVE, AnalysisOutcome.INCONCLUSIVE,
                            AnalysisTerminationReason.BUDGET_EXHAUSTED);
                }
                lifecycle = consumeSemanticBudget(lifecycle, AnalysisBudgetActivity.SEMANTIC_QUERY);
                recordSemanticCall(semanticCallsByNeed, informationNeed.id());
                SemanticQueryResult initialResult = invokeSemanticQuery(query);
                int callsSoFar = semanticCallsByNeed.getOrDefault(informationNeed.id(), 0);
                boolean retryAllowed = semanticRetryPolicy.shouldRetry(
                        initialResult, callsSoFar, lifecycle.state().budget());
                SemanticStepResult semanticStep = semanticResultHandler.handleForExecution(
                        lifecycle.state(), query, initialResult, retryAllowed);
                lifecycle = withState(lifecycle, semanticStep.state());

                if (semanticStep.disposition() == SemanticStepDisposition.RETRYABLE && retryAllowed) {
                    if (isCancellationRequested(lifecycle)) {
                        return conclude(lifecycle, AttemptOutcome.CANCELLED, AnalysisOutcome.CANCELLED,
                                AnalysisTerminationReason.CANCELLED);
                    }
                    if (hasReachedSemanticCallLimit(semanticCallsByNeed, informationNeed.id())) {
                        return conclude(lifecycle, AttemptOutcome.INCONCLUSIVE, AnalysisOutcome.INCONCLUSIVE,
                                AnalysisTerminationReason.NO_PROGRESS);
                    }
                    lifecycle = consumeSemanticBudget(lifecycle, AnalysisBudgetActivity.SEMANTIC_RETRY);
                    recordSemanticCall(semanticCallsByNeed, informationNeed.id());
                    SemanticQueryResult retryResult = invokeSemanticQuery(query);
                    semanticStep = semanticResultHandler.handleForExecution(
                            lifecycle.state(), query, retryResult, false);
                    lifecycle = withState(lifecycle, semanticStep.state());
                }

                DiscoveryPinResult discoveryPinResult = pinDiscoveries(
                        lifecycle, semanticStep.newDiscoveries());
                lifecycle = discoveryPinResult.lifecycle();
                if (discoveryPinResult.disposition() == DiscoveryPinDisposition.CANCELLED) {
                    return conclude(lifecycle, AttemptOutcome.CANCELLED, AnalysisOutcome.CANCELLED,
                            AnalysisTerminationReason.CANCELLED);
                }
                if (discoveryPinResult.disposition() == DiscoveryPinDisposition.BUDGET_EXHAUSTED) {
                    return conclude(lifecycle, AttemptOutcome.INCONCLUSIVE, AnalysisOutcome.INCONCLUSIVE,
                            AnalysisTerminationReason.BUDGET_EXHAUSTED);
                }
                if (semanticStep.disposition() == SemanticStepDisposition.STALE) {
                    if (lifecycle.revisionRestartCount() >= 1) {
                        return conclude(lifecycle, AttemptOutcome.STALE, AnalysisOutcome.INCONCLUSIVE,
                                AnalysisTerminationReason.REVISION_RESTART_LIMIT);
                    }
                    if (isCancellationRequested(lifecycle)) {
                        return conclude(lifecycle, AttemptOutcome.CANCELLED, AnalysisOutcome.CANCELLED,
                                AnalysisTerminationReason.CANCELLED);
                    }
                    lifecycle = attemptLifecycleManager.restartAfterRevisionMismatchForExecution(
                            lifecycle, command, analysisCancellationPort);
                    progressHistory.clear();
                    semanticCallsByNeed.clear();
                    continue;
                }
                if (semanticStep.disposition() == SemanticStepDisposition.BLOCKED
                        || semanticStep.disposition() == SemanticStepDisposition.FAILED) {
                    return concludeForSemanticFailure(
                            lifecycle, semanticStep.disposition(), semanticStep.failure());
                }

                progressHistory.add(ProgressFingerprint.from(lifecycle.state()));
                NoProgressEvaluation noProgress = noProgressPolicy.evaluate(progressHistory);
                if (noProgress.terminate()) {
                    return conclude(lifecycle, AttemptOutcome.INCONCLUSIVE, AnalysisOutcome.INCONCLUSIVE,
                            AnalysisTerminationReason.NO_PROGRESS);
                }
            }
            throw new IllegalStateException("active bounded analysis loop ended without a terminal result");
        } catch (AttemptPreparationException exception) {
            return concludeForPreparationFailure(exception);
        } catch (AttemptPreparationCancelledException exception) {
            return conclude(exception.lastCommittedLifecycle(), AttemptOutcome.CANCELLED, AnalysisOutcome.CANCELLED,
                    AnalysisTerminationReason.CANCELLED);
        } catch (AttemptPreparationBudgetExhaustedException exception) {
            return conclude(exception.lastCommittedLifecycle(), AttemptOutcome.INCONCLUSIVE, AnalysisOutcome.INCONCLUSIVE,
                    AnalysisTerminationReason.BUDGET_EXHAUSTED);
        } catch (AttemptLifecycleExternalFailureException exception) {
            throw executionFailure(exception.lastCommittedLifecycle(), exception);
        } catch (SemanticResultHandlingException exception) {
            throw executionFailure(withState(lifecycle, exception.lastCommittedState()), exception);
        } catch (AnalysisExecutionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw executionFailure(lifecycle, exception);
        }
    }

    private AnalysisExecutionResult concludeForGoal(
            AttemptLifecycle lifecycle,
            GoalEvaluation goalEvaluation) {
        AnalysisOutcome outcome = goalEvaluation.outcome().orElseThrow();
        return switch (outcome) {
            case COMPLETED -> conclude(lifecycle, AttemptOutcome.COMPLETED, outcome,
                    AnalysisTerminationReason.GOAL_COMPLETED);
            case INCONCLUSIVE -> conclude(lifecycle, AttemptOutcome.INCONCLUSIVE, outcome,
                    AnalysisTerminationReason.NO_PROGRESS);
            case FAILED -> conclude(lifecycle, AttemptOutcome.FAILED, outcome,
                    AnalysisTerminationReason.RUNTIME_FAILURE);
            case CANCELLED -> conclude(lifecycle, AttemptOutcome.CANCELLED, outcome,
                    AnalysisTerminationReason.CANCELLED);
        };
    }

    private AnalysisExecutionResult concludeForPlanning(
            AttemptLifecycle lifecycle,
            PlanningStatus planningStatus) {
        AnalysisTerminationReason reason = switch (planningStatus) {
            case CAPABILITY_MISSING -> AnalysisTerminationReason.CAPABILITY_MISSING;
            case PREREQUISITE_MISSING -> AnalysisTerminationReason.PREREQUISITE_MISSING;
            case BUDGET_EXHAUSTED -> AnalysisTerminationReason.BUDGET_EXHAUSTED;
            case AMBIGUOUS_CAPABILITY -> AnalysisTerminationReason.SEMANTIC_AMBIGUOUS;
            case PLANNED -> throw new IllegalArgumentException("planned capability must not be concluded as blocked");
        };
        return conclude(lifecycle, AttemptOutcome.INCONCLUSIVE, AnalysisOutcome.INCONCLUSIVE, reason);
    }

    private AnalysisExecutionResult concludeForSemanticFailure(
            AttemptLifecycle lifecycle,
            SemanticStepDisposition disposition,
            Optional<SemanticFailure> failure) {
        SemanticFailure semanticFailure = failure.orElseThrow();
        AnalysisTerminationReason reason = switch (semanticFailure.code()) {
            case AMBIGUOUS_TARGET -> AnalysisTerminationReason.SEMANTIC_AMBIGUOUS;
            case FORBIDDEN -> AnalysisTerminationReason.SEMANTIC_FORBIDDEN;
            case CAPABILITY_MISSING -> AnalysisTerminationReason.CAPABILITY_MISSING;
            case NOT_READY, TIMEOUT, REPOSITORY_NOT_FOUND, PROTOCOL_ERROR,
                    ENGINE_UNAVAILABLE, ENGINE_FAILURE, PARTIAL_RESULT, REVISION_MISMATCH ->
                    AnalysisTerminationReason.SEMANTIC_UNAVAILABLE;
        };
        if (disposition == SemanticStepDisposition.FAILED) {
            return conclude(lifecycle, AttemptOutcome.FAILED, AnalysisOutcome.FAILED, reason);
        }
        return conclude(lifecycle, AttemptOutcome.INCONCLUSIVE, AnalysisOutcome.INCONCLUSIVE, reason);
    }

    private AnalysisExecutionResult concludeForPreparationFailure(
            AttemptPreparationException exception) {
        SemanticFailure failure = exception.semanticFailure();
        SemanticStepDisposition disposition = switch (failure.code()) {
            case PROTOCOL_ERROR, ENGINE_FAILURE -> SemanticStepDisposition.FAILED;
            default -> SemanticStepDisposition.BLOCKED;
        };
        return concludeForSemanticFailure(
                exception.lastCommittedLifecycle(), disposition, Optional.of(failure));
    }

    private DiscoveryPinResult pinDiscoveries(
            AttemptLifecycle lifecycle,
            List<RepositoryDiscovery> discoveries) {
        List<RepositoryDiscovery> sortedDiscoveries = discoveries.stream()
                .sorted(Comparator.comparing(RepositoryDiscovery::repositoryId))
                .toList();
        AttemptLifecycle current = lifecycle;
        for (RepositoryDiscovery discovery : sortedDiscoveries) {
            if (isCancellationRequested(current)) {
                return new DiscoveryPinResult(current, DiscoveryPinDisposition.CANCELLED);
            }
            boolean semanticDiscoveryCommitted = current.state().repositoryScope()
                    .selection(discovery.repositoryId())
                    .filter(selection -> selection.discoverySource() == RepositoryDiscoverySource.SEMANTIC_EVIDENCE)
                    .isPresent();
            if (!semanticDiscoveryCommitted) {
                throw new IllegalStateException("semantic discovery was not committed to the repository scope");
            }
            boolean revisionPinned = current.state().revisionVector()
                    .revisionOf(discovery.repositoryId())
                    .isPresent();
            if (!revisionPinned && !current.state().budget().hasStepRemaining()) {
                return new DiscoveryPinResult(current, DiscoveryPinDisposition.BUDGET_EXHAUSTED);
            }
            current = attemptLifecycleManager.pinDiscoveredRepository(current, discovery.repositoryId());
        }
        return new DiscoveryPinResult(current, DiscoveryPinDisposition.COMPLETE);
    }

    private AttemptLifecycle consumeSemanticBudget(
            AttemptLifecycle lifecycle,
            AnalysisBudgetActivity activity) {
        AnalysisState consumedState = transitionCommitter.apply(lifecycle.state(), new AnalysisEvent.BudgetConsumed(
                lifecycle.state().runId(),
                lifecycle.state().attemptId(),
                lifecycle.state().stateRevision(),
                activity));
        return withState(lifecycle, consumedState);
    }

    private SemanticQuery queryFor(PlannedCapability plannedCapability) {
        SemanticTarget semanticTarget = plannedCapability.semanticTarget().orElseThrow();
        return new SemanticQuery(
                plannedCapability.capability().qualifiedName(),
                plannedCapability.informationNeed(),
                semanticTarget,
                plannedCapability.repositoryId(),
                plannedCapability.expectedRevision());
    }

    private SemanticQueryResult invokeSemanticQuery(SemanticQuery query) {
        return Objects.requireNonNull(semanticQueryPort.query(query), "semantic query port must return a result");
    }

    private boolean hasReachedSemanticCallLimit(
            Map<InformationNeedId, Integer> semanticCallsByNeed,
            InformationNeedId informationNeedId) {
        return semanticCallsByNeed.getOrDefault(informationNeedId, 0) >= 2;
    }

    private void recordSemanticCall(
            Map<InformationNeedId, Integer> semanticCallsByNeed,
            InformationNeedId informationNeedId) {
        semanticCallsByNeed.merge(informationNeedId, 1, Integer::sum);
    }

    private boolean isCancellationRequested(AttemptLifecycle lifecycle) {
        return analysisCancellationPort.isCancellationRequested(lifecycle.run().id());
    }

    private AttemptLifecycle withState(AttemptLifecycle lifecycle, AnalysisState state) {
        return new AttemptLifecycle(lifecycle.run(), state, lifecycle.revisionRestartCount());
    }

    private AnalysisExecutionResult conclude(
            AttemptLifecycle lifecycle,
            AttemptOutcome attemptOutcome,
            AnalysisOutcome analysisOutcome,
            AnalysisTerminationReason reason) {
        try {
            AttemptLifecycle concluded = attemptLifecycleManager.conclude(lifecycle, attemptOutcome, analysisOutcome);
            return new AnalysisExecutionResult(concluded.run(), concluded.state(), reason);
        } catch (AnalysisTransitionCommitException exception) {
            throw executionFailure(lifecycle, exception);
        }
    }

    private AnalysisExecutionException executionFailure(
            AttemptLifecycle lifecycle,
            RuntimeException cause) {
        return new AnalysisExecutionException(
                AnalysisTerminationReason.RUNTIME_FAILURE,
                lifecycle.state(),
                cause);
    }

    private record DiscoveryPinResult(
            AttemptLifecycle lifecycle,
            DiscoveryPinDisposition disposition) {

        private DiscoveryPinResult {
            Objects.requireNonNull(lifecycle, "attempt lifecycle must not be null");
            Objects.requireNonNull(disposition, "discovery pin disposition must not be null");
        }
    }

    private enum DiscoveryPinDisposition {
        COMPLETE,
        CANCELLED,
        BUDGET_EXHAUSTED
    }
}
