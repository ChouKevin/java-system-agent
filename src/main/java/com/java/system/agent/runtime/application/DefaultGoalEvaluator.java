package com.java.system.agent.runtime.application;

import com.java.system.agent.runtime.domain.AnalysisOutcome;
import com.java.system.agent.runtime.domain.AnalysisState;
import com.java.system.agent.runtime.domain.AnalysisStatus;
import com.java.system.agent.runtime.domain.Goal;
import com.java.system.agent.runtime.domain.InformationNeedId;

import java.util.Objects;
import java.util.Optional;

public final class DefaultGoalEvaluator implements GoalEvaluator {

    @Override
    public GoalEvaluation evaluate(
            Goal goal,
            AnalysisState state,
            Optional<GoalBlocker> blocker) {
        Objects.requireNonNull(goal, "goal must not be null");
        Objects.requireNonNull(state, "analysis state must not be null");
        Objects.requireNonNull(blocker, "goal blocker must not be null");

        if (state.status() == AnalysisStatus.FAILED
                || blocker.filter(GoalBlocker.INVARIANT_FAILURE::equals).isPresent()) {
            return terminal(AnalysisOutcome.FAILED, "analysis invariant or system failure");
        }
        if (state.status() == AnalysisStatus.CANCELLED
                || blocker.filter(GoalBlocker.CANCELLED::equals).isPresent()) {
            return terminal(AnalysisOutcome.CANCELLED, "analysis was cancelled");
        }
        if (blocker.isPresent()) {
            return terminal(
                    AnalysisOutcome.INCONCLUSIVE,
                    "analysis cannot make reliable progress: " + blocker.orElseThrow());
        }
        boolean allRequiredNeedsResolved = goal.requiredNeedIds().stream()
                .allMatch(state.resolvedNeedIds()::contains);
        boolean allRequiredNeedsHaveEvidence = goal.requiredNeedIds().stream()
                .allMatch(requiredNeedId -> hasEvidence(state, requiredNeedId));
        if (allRequiredNeedsResolved && allRequiredNeedsHaveEvidence) {
            return terminal(AnalysisOutcome.COMPLETED, "all required information needs have evidence");
        }
        return new GoalEvaluation(
                GoalEvaluationStatus.CONTINUE,
                Optional.empty(),
                "required information needs remain unresolved");
    }

    private boolean hasEvidence(AnalysisState state, InformationNeedId informationNeedId) {
        return state.evidenceBindings().stream()
                .anyMatch(binding -> binding.informationNeedId().equals(informationNeedId));
    }

    private GoalEvaluation terminal(AnalysisOutcome outcome, String reason) {
        return new GoalEvaluation(GoalEvaluationStatus.TERMINAL, Optional.of(outcome), reason);
    }
}
