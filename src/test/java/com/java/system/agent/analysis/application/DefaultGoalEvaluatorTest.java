package com.java.system.agent.analysis.application;

import com.java.system.agent.analysis.domain.AnalysisAttemptId;
import com.java.system.agent.analysis.domain.AnalysisBudget;
import com.java.system.agent.analysis.domain.AnalysisRunId;
import com.java.system.agent.analysis.domain.AnalysisState;
import com.java.system.agent.analysis.domain.AnalysisStatus;
import com.java.system.agent.analysis.domain.AnalysisOutcome;
import com.java.system.agent.analysis.domain.Goal;
import com.java.system.agent.analysis.domain.InformationNeedId;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultGoalEvaluatorTest {

    private final GoalEvaluator evaluator = new DefaultGoalEvaluator();

    @Test
    void completesOnlyWhenEveryRequiredNeedHasAcceptedEvidence() {
        InformationNeedId requiredNeed = new InformationNeedId("need-entry-point");
        Goal goal = new Goal("Answer the order flow question", Set.of(requiredNeed));
        AnalysisState completed = GoalStateFixture.resolved(requiredNeed);
        AnalysisState unresolved = AnalysisState.initial(
                new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"),
                AnalysisBudget.of(5, 2));

        GoalEvaluation completedEvaluation = evaluator.evaluate(goal, completed, Optional.empty());
        GoalEvaluation unresolvedEvaluation = evaluator.evaluate(goal, unresolved, Optional.empty());

        assertThat(completedEvaluation.outcome()).contains(AnalysisOutcome.COMPLETED);
        assertThat(unresolvedEvaluation.status()).isEqualTo(GoalEvaluationStatus.CONTINUE);
    }

    @Test
    void mapsBlockingDiagnosisToInconclusive() {
        Goal goal = new Goal(
                "Answer the order flow question",
                Set.of(new InformationNeedId("need-entry-point")));
        AnalysisState state = AnalysisState.initial(
                new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"),
                AnalysisBudget.of(5, 2));

        GoalEvaluation capabilityMissing = evaluator.evaluate(
                goal, state, Optional.of(GoalBlocker.CAPABILITY_MISSING));
        GoalEvaluation noProgress = evaluator.evaluate(
                goal, state, Optional.of(GoalBlocker.NO_PROGRESS));

        assertThat(capabilityMissing.outcome()).contains(AnalysisOutcome.INCONCLUSIVE);
        assertThat(noProgress.outcome()).contains(AnalysisOutcome.INCONCLUSIVE);
    }

    @Test
    void preservesFailedAndCancelledTerminalMeaning() {
        Goal goal = new Goal("Answer the question", Set.of());
        AnalysisState state = AnalysisState.initial(
                new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"),
                AnalysisBudget.of(5, 2));

        GoalEvaluation failed = evaluator.evaluate(
                goal,
                GoalStateFixture.withStatus(state, AnalysisStatus.FAILED),
                Optional.of(GoalBlocker.INVARIANT_FAILURE));
        GoalEvaluation cancelled = evaluator.evaluate(
                goal,
                GoalStateFixture.withStatus(state, AnalysisStatus.CANCELLED),
                Optional.of(GoalBlocker.CANCELLED));

        assertThat(failed.outcome()).contains(AnalysisOutcome.FAILED);
        assertThat(cancelled.outcome()).contains(AnalysisOutcome.CANCELLED);
    }
}
