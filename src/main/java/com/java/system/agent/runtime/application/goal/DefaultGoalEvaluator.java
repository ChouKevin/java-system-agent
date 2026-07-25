package com.java.system.agent.runtime.application.goal;

import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.run.AttemptState;
import com.java.system.agent.runtime.domain.run.AttemptStatus;
import com.java.system.agent.runtime.domain.need.Goal;
import com.java.system.agent.runtime.domain.need.InformationNeedId;

import java.util.Objects;
import java.util.Optional;

/**
 * {@link GoalEvaluator} 的唯一實作：判斷 attempt 是否已經可以終止
 *
 * <p>由 {@link com.java.system.agent.runtime.application.BoundedAnalysisLoop} 在迴圈每一輪
 * 開頭呼叫，依序檢查失敗、取消、外部傳入的封鎖原因，最後檢查目標的所有必要
 * information need 是否都已解決且有證據，據此回傳
 * {@link GoalEvaluationStatus#CONTINUE} 或帶著 {@code RunOutcome} 的
 * {@link GoalEvaluationStatus#TERMINAL}</p>
 *
 * <p>終止與否是純規則判斷——只讀狀態與旗標，完全不呼叫任何 LLM，是刻意設計成確定性的
 * policy</p>
 */
public final class DefaultGoalEvaluator implements GoalEvaluator {

    @Override
    public GoalEvaluation evaluate(
            Goal goal,
            AttemptState state,
            Optional<GoalBlockReason> blocker) {
        Objects.requireNonNull(goal, "goal must not be null");
        Objects.requireNonNull(state, "analysis state must not be null");
        Objects.requireNonNull(blocker, "goal blocker must not be null");

        if (state.status() == AttemptStatus.FAILED
                || blocker.filter(GoalBlockReason.INVARIANT_FAILURE::equals).isPresent()) {
            return terminal(RunOutcome.FAILED, "analysis invariant or system failure");
        }
        if (state.status() == AttemptStatus.CANCELLED
                || blocker.filter(GoalBlockReason.CANCELLED::equals).isPresent()) {
            return terminal(RunOutcome.CANCELLED, "analysis was cancelled");
        }
        if (blocker.isPresent()) {
            return terminal(
                    RunOutcome.INCONCLUSIVE,
                    "analysis cannot make reliable progress: " + blocker.orElseThrow());
        }
        boolean allRequiredNeedsResolved = goal.requiredNeedIds().stream()
                .allMatch(state.resolvedNeedIds()::contains);
        boolean allRequiredNeedsHaveEvidence = goal.requiredNeedIds().stream()
                .allMatch(requiredNeedId -> hasEvidence(state, requiredNeedId));
        if (allRequiredNeedsResolved && allRequiredNeedsHaveEvidence) {
            return terminal(RunOutcome.COMPLETED, "all required information needs have evidence");
        }
        return new GoalEvaluation(
                GoalEvaluationStatus.CONTINUE,
                Optional.empty(),
                "required information needs remain unresolved");
    }

    private boolean hasEvidence(AttemptState state, InformationNeedId informationNeedId) {
        return state.evidenceBindings().stream()
                .anyMatch(binding -> binding.informationNeedId().equals(informationNeedId));
    }

    private GoalEvaluation terminal(RunOutcome outcome, String reason) {
        return new GoalEvaluation(GoalEvaluationStatus.TERMINAL, Optional.of(outcome), reason);
    }
}
