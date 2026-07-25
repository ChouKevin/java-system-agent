package com.java.system.agent.runtime.application.goal;

import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptState;
import com.java.system.agent.runtime.domain.run.AttemptStatus;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.need.Goal;
import com.java.system.agent.runtime.domain.need.InformationNeedId;
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
        AttemptState completed = GoalStateFixture.resolved(requiredNeed);
        AttemptState unresolved = AttemptState.initial(
                new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"),
                AttemptBudget.of(5, 2));

        GoalEvaluation completedEvaluation = evaluator.evaluate(goal, completed, Optional.empty());
        GoalEvaluation unresolvedEvaluation = evaluator.evaluate(goal, unresolved, Optional.empty());

        assertThat(completedEvaluation.outcome()).contains(RunOutcome.COMPLETED);
        assertThat(unresolvedEvaluation.status()).isEqualTo(GoalEvaluationStatus.CONTINUE);
    }

    @Test
    void mapsBlockingDiagnosisToInconclusive() {
        Goal goal = new Goal(
                "Answer the order flow question",
                Set.of(new InformationNeedId("need-entry-point")));
        AttemptState state = AttemptState.initial(
                new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"),
                AttemptBudget.of(5, 2));

        GoalEvaluation capabilityMissing = evaluator.evaluate(
                goal, state, Optional.of(GoalBlockReason.CAPABILITY_MISSING));
        GoalEvaluation noProgress = evaluator.evaluate(
                goal, state, Optional.of(GoalBlockReason.NO_PROGRESS));

        assertThat(capabilityMissing.outcome()).contains(RunOutcome.INCONCLUSIVE);
        assertThat(noProgress.outcome()).contains(RunOutcome.INCONCLUSIVE);
    }

    @Test
    void preservesFailedAndCancelledTerminalMeaning() {
        Goal goal = new Goal("Answer the question", Set.of());
        AttemptState state = AttemptState.initial(
                new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"),
                AttemptBudget.of(5, 2));

        GoalEvaluation failed = evaluator.evaluate(
                goal,
                GoalStateFixture.withStatus(state, AttemptStatus.FAILED),
                Optional.of(GoalBlockReason.INVARIANT_FAILURE));
        GoalEvaluation cancelled = evaluator.evaluate(
                goal,
                GoalStateFixture.withStatus(state, AttemptStatus.CANCELLED),
                Optional.of(GoalBlockReason.CANCELLED));

        assertThat(failed.outcome()).contains(RunOutcome.FAILED);
        assertThat(cancelled.outcome()).contains(RunOutcome.CANCELLED);
    }
}
