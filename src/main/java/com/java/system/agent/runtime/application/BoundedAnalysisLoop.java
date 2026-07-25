package com.java.system.agent.runtime.application;

import com.java.system.agent.runtime.application.goal.GoalEvaluation;
import com.java.system.agent.runtime.application.goal.GoalEvaluationStatus;
import com.java.system.agent.runtime.application.goal.GoalEvaluator;
import com.java.system.agent.runtime.application.goal.NoProgressEvaluation;
import com.java.system.agent.runtime.application.goal.NoProgressPolicy;
import com.java.system.agent.runtime.application.goal.ProgressFingerprint;
import com.java.system.agent.runtime.application.lifecycle.AttemptLifecycle;
import com.java.system.agent.runtime.application.lifecycle.AttemptLifecycleExternalFailureException;
import com.java.system.agent.runtime.application.lifecycle.AttemptLifecycleManager;
import com.java.system.agent.runtime.application.lifecycle.AttemptPreparationBudgetExhaustedException;
import com.java.system.agent.runtime.application.lifecycle.AttemptPreparationCancelledException;
import com.java.system.agent.runtime.application.lifecycle.AttemptPreparationException;
import com.java.system.agent.runtime.application.planning.InformationNeedPlanner;
import com.java.system.agent.runtime.application.planning.PlannedCapability;
import com.java.system.agent.runtime.application.planning.PlanningResult;
import com.java.system.agent.runtime.application.planning.PlanningStatus;
import com.java.system.agent.runtime.application.planning.SemanticCapabilityRegistry;
import com.java.system.agent.runtime.application.semantic.SemanticResultHandlingException;
import com.java.system.agent.runtime.application.semantic.SemanticResultInterpreter;
import com.java.system.agent.runtime.application.semantic.SemanticRetryPolicy;
import com.java.system.agent.runtime.application.semantic.SemanticStepOutcome;
import com.java.system.agent.runtime.application.semantic.SemanticStepResult;
import com.java.system.agent.runtime.application.state.AnalysisEvent;
import com.java.system.agent.runtime.application.state.AnalysisTransitionCommitException;
import com.java.system.agent.runtime.application.state.BudgetedActivity;
import com.java.system.agent.runtime.application.state.TransitionCommitter;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.run.AttemptState;
import com.java.system.agent.runtime.domain.run.AttemptOutcome;
import com.java.system.agent.runtime.domain.need.InformationNeed;
import com.java.system.agent.runtime.domain.need.InformationNeedId;
import com.java.system.agent.runtime.domain.scope.RepositoryDiscoverySource;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.evidence.SemanticTarget;
import com.java.system.agent.runtime.domain.evidence.SemanticTargetKind;
import com.java.system.agent.runtime.port.in.AnalysisExecutionCommand;
import com.java.system.agent.runtime.port.in.AnalysisExecutionException;
import com.java.system.agent.runtime.port.in.AnalysisExecutionResult;
import com.java.system.agent.runtime.port.in.AnalysisTerminationReason;
import com.java.system.agent.runtime.port.in.ExecuteAnalysisUseCase;
import com.java.system.agent.runtime.port.out.AnalysisCancellationPort;
import com.java.system.agent.runtime.port.out.RepositoryDiscovery;
import com.java.system.agent.runtime.port.out.SemanticFailure;
import com.java.system.agent.runtime.port.out.SemanticFailureCode;
import com.java.system.agent.runtime.port.out.SemanticQuery;
import com.java.system.agent.runtime.port.out.SemanticQueryPort;
import com.java.system.agent.runtime.port.out.SemanticQueryResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 驅動一次有界分析 run 的唯一入口，是整個 application 層唯一橫跨所有階段的類別
 *
 * <p>依序驅動：{@link com.java.system.agent.runtime.application.lifecycle lifecycle}
 * 準備 attempt →
 * {@link com.java.system.agent.runtime.application.goal goal} 判斷是否已可終止 →
 * {@link com.java.system.agent.runtime.application.planning planning} 選擇語意能力 →
 * {@link com.java.system.agent.runtime.application.state state} 扣預算 →
 * {@link com.java.system.agent.runtime.application.semantic semantic} 呼叫語意查詢並轉譯結果
 * （必要時重試）→
 * state 釘選新發現的 repository 或在 revision 過期時交回 lifecycle 重啟 →
 * goal 檢查是否停滯不前 → 迴圈繼續或由 lifecycle 收斂結束</p>
 *
 * <p>它本身不持有任何狀態：每一次狀態變更都必須經過
 * {@link com.java.system.agent.runtime.application.state.TransitionCommitter}，
 * 這裡只負責串接各階段協作者並依它們回傳的結果決定下一步</p>
 */
public final class BoundedAnalysisLoop implements ExecuteAnalysisUseCase {

    private final AttemptLifecycleManager attemptLifecycleManager;
    private final GoalEvaluator goalEvaluator;
    private final InformationNeedPlanner informationNeedPlanner;
    private final SemanticCapabilityRegistry semanticCapabilityRegistry;
    private final SemanticRetryPolicy semanticRetryPolicy;
    private final SemanticResultInterpreter semanticResultHandler;
    private final NoProgressPolicy noProgressPolicy;
    private final TransitionCommitter transitionCommitter;
    private final SemanticQueryPort semanticQueryPort;
    private final AnalysisCancellationPort analysisCancellationPort;

    public BoundedAnalysisLoop(
            AttemptLifecycleManager attemptLifecycleManager,
            GoalEvaluator goalEvaluator,
            InformationNeedPlanner informationNeedPlanner,
            SemanticCapabilityRegistry semanticCapabilityRegistry,
            SemanticRetryPolicy semanticRetryPolicy,
            SemanticResultInterpreter semanticResultHandler,
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
            return conclude(exception.lastCommittedLifecycle(), AttemptOutcome.CANCELLED, RunOutcome.CANCELLED,
                    AnalysisTerminationReason.CANCELLED);
        } catch (AttemptPreparationBudgetExhaustedException exception) {
            return conclude(exception.lastCommittedLifecycle(), AttemptOutcome.INCONCLUSIVE, RunOutcome.INCONCLUSIVE,
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
                    return conclude(lifecycle, AttemptOutcome.CANCELLED, RunOutcome.CANCELLED,
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
                    return conclude(lifecycle, AttemptOutcome.INCONCLUSIVE, RunOutcome.INCONCLUSIVE,
                            AnalysisTerminationReason.PREREQUISITE_MISSING);
                }
                SemanticQuery query = queryFor(plannedCapability);

                if (isCancellationRequested(lifecycle)) {
                    return conclude(lifecycle, AttemptOutcome.CANCELLED, RunOutcome.CANCELLED,
                            AnalysisTerminationReason.CANCELLED);
                }
                if (hasReachedSemanticCallLimit(semanticCallsByNeed, informationNeed.id())) {
                    return conclude(lifecycle, AttemptOutcome.INCONCLUSIVE, RunOutcome.INCONCLUSIVE,
                            AnalysisTerminationReason.NO_PROGRESS);
                }
                if (!lifecycle.state().budget().hasStepRemaining()) {
                    return conclude(lifecycle, AttemptOutcome.INCONCLUSIVE, RunOutcome.INCONCLUSIVE,
                            AnalysisTerminationReason.BUDGET_EXHAUSTED);
                }
                lifecycle = consumeSemanticBudget(lifecycle, BudgetedActivity.SEMANTIC_QUERY);
                recordSemanticCall(semanticCallsByNeed, informationNeed.id());
                SemanticQueryResult initialResult = invokeSemanticQuery(query);
                int callsSoFar = semanticCallsByNeed.getOrDefault(informationNeed.id(), 0);
                boolean retryAllowed = semanticRetryPolicy.shouldRetry(
                        initialResult, callsSoFar, lifecycle.state().budget());
                SemanticStepResult semanticStep = semanticResultHandler.handleForExecution(
                        lifecycle.state(), query, initialResult, retryAllowed);
                lifecycle = withState(lifecycle, semanticStep.state());

                if (semanticStep.disposition() == SemanticStepOutcome.RETRYABLE && retryAllowed) {
                    if (isCancellationRequested(lifecycle)) {
                        return conclude(lifecycle, AttemptOutcome.CANCELLED, RunOutcome.CANCELLED,
                                AnalysisTerminationReason.CANCELLED);
                    }
                    if (hasReachedSemanticCallLimit(semanticCallsByNeed, informationNeed.id())) {
                        return conclude(lifecycle, AttemptOutcome.INCONCLUSIVE, RunOutcome.INCONCLUSIVE,
                                AnalysisTerminationReason.NO_PROGRESS);
                    }
                    lifecycle = consumeSemanticBudget(lifecycle, BudgetedActivity.SEMANTIC_RETRY);
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
                    return conclude(lifecycle, AttemptOutcome.CANCELLED, RunOutcome.CANCELLED,
                            AnalysisTerminationReason.CANCELLED);
                }
                if (discoveryPinResult.disposition() == DiscoveryPinDisposition.BUDGET_EXHAUSTED) {
                    return conclude(lifecycle, AttemptOutcome.INCONCLUSIVE, RunOutcome.INCONCLUSIVE,
                            AnalysisTerminationReason.BUDGET_EXHAUSTED);
                }
                if (semanticStep.disposition() == SemanticStepOutcome.STALE) {
                    if (lifecycle.revisionRestartCount() >= 1) {
                        return conclude(lifecycle, AttemptOutcome.STALE, RunOutcome.INCONCLUSIVE,
                                AnalysisTerminationReason.REVISION_RESTART_LIMIT);
                    }
                    if (isCancellationRequested(lifecycle)) {
                        return conclude(lifecycle, AttemptOutcome.CANCELLED, RunOutcome.CANCELLED,
                                AnalysisTerminationReason.CANCELLED);
                    }
                    lifecycle = attemptLifecycleManager.restartAfterRevisionMismatchForExecution(
                            lifecycle, command, analysisCancellationPort);
                    progressHistory.clear();
                    semanticCallsByNeed.clear();
                    continue;
                }
                if (semanticStep.disposition() == SemanticStepOutcome.BLOCKED
                        || semanticStep.disposition() == SemanticStepOutcome.FAILED) {
                    return concludeForSemanticFailure(
                            lifecycle, semanticStep.disposition(), semanticStep.failure());
                }

                progressHistory.add(ProgressFingerprint.from(lifecycle.state()));
                NoProgressEvaluation noProgress = noProgressPolicy.evaluate(progressHistory);
                if (noProgress.terminate()) {
                    return conclude(lifecycle, AttemptOutcome.INCONCLUSIVE, RunOutcome.INCONCLUSIVE,
                            AnalysisTerminationReason.NO_PROGRESS);
                }
            }
            throw new IllegalStateException("active bounded analysis loop ended without a terminal result");
        } catch (AttemptPreparationException exception) {
            return concludeForPreparationFailure(exception);
        } catch (AttemptPreparationCancelledException exception) {
            return conclude(exception.lastCommittedLifecycle(), AttemptOutcome.CANCELLED, RunOutcome.CANCELLED,
                    AnalysisTerminationReason.CANCELLED);
        } catch (AttemptPreparationBudgetExhaustedException exception) {
            return conclude(exception.lastCommittedLifecycle(), AttemptOutcome.INCONCLUSIVE, RunOutcome.INCONCLUSIVE,
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
        AnalysisConclusion conclusion = AnalysisConclusionMapper.forGoal(goalEvaluation);
        return conclude(lifecycle, conclusion.attemptOutcome(), conclusion.runOutcome(), conclusion.reason());
    }

    private AnalysisExecutionResult concludeForPlanning(
            AttemptLifecycle lifecycle,
            PlanningStatus planningStatus) {
        AnalysisConclusion conclusion = AnalysisConclusionMapper.forPlanning(planningStatus);
        return conclude(lifecycle, conclusion.attemptOutcome(), conclusion.runOutcome(), conclusion.reason());
    }

    private AnalysisExecutionResult concludeForSemanticFailure(
            AttemptLifecycle lifecycle,
            SemanticStepOutcome disposition,
            Optional<SemanticFailure> failure) {
        AnalysisConclusion conclusion = AnalysisConclusionMapper.forSemanticFailure(
                disposition, failure.orElseThrow());
        return conclude(lifecycle, conclusion.attemptOutcome(), conclusion.runOutcome(), conclusion.reason());
    }

    private AnalysisExecutionResult concludeForPreparationFailure(
            AttemptPreparationException exception) {
        AnalysisConclusion conclusion = AnalysisConclusionMapper.forPreparationFailure(exception);
        return conclude(exception.lastCommittedLifecycle(),
                conclusion.attemptOutcome(), conclusion.runOutcome(), conclusion.reason());
    }

    private DiscoveryPinResult pinDiscoveries(
            AttemptLifecycle lifecycle,
            List<RepositoryDiscovery> discoveries) {
        List<RepositoryDiscovery> sortedDiscoveries = discoveries.stream()
                .sorted(Comparator.comparing(RepositoryDiscovery::repositoryId))
                .toList();
        AttemptLifecycle current = lifecycle;
        for (RepositoryDiscovery discovery : sortedDiscoveries) {
            if (isDiscoveryCancellationRequested(current)) {
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

    private boolean isDiscoveryCancellationRequested(AttemptLifecycle lifecycle) {
        try {
            return isCancellationRequested(lifecycle);
        } catch (RuntimeException exception) {
            throw new AttemptLifecycleExternalFailureException(
                    "analysis cancellation check failed while pinning discoveries",
                    lifecycle,
                    exception);
        }
    }

    private AttemptLifecycle consumeSemanticBudget(
            AttemptLifecycle lifecycle,
            BudgetedActivity activity) {
        AttemptState consumedState = transitionCommitter.apply(lifecycle.state(), new AnalysisEvent.BudgetConsumed(
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

    private AttemptLifecycle withState(AttemptLifecycle lifecycle, AttemptState state) {
        return new AttemptLifecycle(lifecycle.run(), state, lifecycle.revisionRestartCount());
    }

    private AnalysisExecutionResult conclude(
            AttemptLifecycle lifecycle,
            AttemptOutcome attemptOutcome,
            RunOutcome analysisOutcome,
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
