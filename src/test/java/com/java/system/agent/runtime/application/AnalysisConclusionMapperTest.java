package com.java.system.agent.runtime.application;

import com.java.system.agent.runtime.application.goal.GoalEvaluation;
import com.java.system.agent.runtime.application.goal.GoalEvaluationStatus;
import com.java.system.agent.runtime.application.lifecycle.AttemptLifecycle;
import com.java.system.agent.runtime.application.lifecycle.AttemptPreparationException;
import com.java.system.agent.runtime.application.planning.PlanningStatus;
import com.java.system.agent.runtime.application.semantic.SemanticStepOutcome;
import com.java.system.agent.runtime.domain.run.AnalysisAttempt;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRun;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.run.AttemptOutcome;
import com.java.system.agent.runtime.domain.run.AttemptState;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import com.java.system.agent.runtime.port.in.AnalysisTerminationReason;
import com.java.system.agent.runtime.port.out.SemanticFailure;
import com.java.system.agent.runtime.port.out.SemanticFailureCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AnalysisConclusionMapper} 四張查表方法的完整分支測試
 *
 * <p>直接斷言每一種來源條件對應的 {@link AttemptOutcome}／{@link RunOutcome}／
 * {@link AnalysisTerminationReason} 三元組，不必透過整個 {@link BoundedAnalysisLoop} 迴圈間接驗證</p>
 */
class AnalysisConclusionMapperTest {

    private static final AnalysisRunId RUN_ID = new AnalysisRunId("run-1");
    private static final AnalysisAttemptId ATTEMPT_ID = new AnalysisAttemptId("attempt-1");
    private static final AttemptBudget BUDGET = AttemptBudget.of(10, 5);

    @Test
    @DisplayName("forGoal maps a COMPLETED run outcome to a completed conclusion")
    void forGoalMapsCompleted() {
        AnalysisConclusion conclusion = AnalysisConclusionMapper.forGoal(goalEvaluation(RunOutcome.COMPLETED));

        assertThat(conclusion).isEqualTo(new AnalysisConclusion(
                AttemptOutcome.COMPLETED, RunOutcome.COMPLETED, AnalysisTerminationReason.GOAL_COMPLETED));
    }

    @Test
    @DisplayName("forGoal maps an INCONCLUSIVE run outcome to NO_PROGRESS unconditionally")
    void forGoalMapsInconclusive() {
        AnalysisConclusion conclusion = AnalysisConclusionMapper.forGoal(goalEvaluation(RunOutcome.INCONCLUSIVE));

        assertThat(conclusion).isEqualTo(new AnalysisConclusion(
                AttemptOutcome.INCONCLUSIVE, RunOutcome.INCONCLUSIVE, AnalysisTerminationReason.NO_PROGRESS));
    }

    @Test
    @DisplayName("forGoal maps a FAILED run outcome to a runtime failure conclusion")
    void forGoalMapsFailed() {
        AnalysisConclusion conclusion = AnalysisConclusionMapper.forGoal(goalEvaluation(RunOutcome.FAILED));

        assertThat(conclusion).isEqualTo(new AnalysisConclusion(
                AttemptOutcome.FAILED, RunOutcome.FAILED, AnalysisTerminationReason.RUNTIME_FAILURE));
    }

    @Test
    @DisplayName("forGoal maps a CANCELLED run outcome to a cancelled conclusion")
    void forGoalMapsCancelled() {
        AnalysisConclusion conclusion = AnalysisConclusionMapper.forGoal(goalEvaluation(RunOutcome.CANCELLED));

        assertThat(conclusion).isEqualTo(new AnalysisConclusion(
                AttemptOutcome.CANCELLED, RunOutcome.CANCELLED, AnalysisTerminationReason.CANCELLED));
    }

    @Test
    @DisplayName("forPlanning maps CAPABILITY_MISSING to an inconclusive conclusion with the matching reason")
    void forPlanningMapsCapabilityMissing() {
        AnalysisConclusion conclusion = AnalysisConclusionMapper.forPlanning(PlanningStatus.CAPABILITY_MISSING);

        assertThat(conclusion).isEqualTo(new AnalysisConclusion(
                AttemptOutcome.INCONCLUSIVE, RunOutcome.INCONCLUSIVE, AnalysisTerminationReason.CAPABILITY_MISSING));
    }

    @Test
    @DisplayName("forPlanning maps PREREQUISITE_MISSING to an inconclusive conclusion with the matching reason")
    void forPlanningMapsPrerequisiteMissing() {
        AnalysisConclusion conclusion = AnalysisConclusionMapper.forPlanning(PlanningStatus.PREREQUISITE_MISSING);

        assertThat(conclusion).isEqualTo(new AnalysisConclusion(
                AttemptOutcome.INCONCLUSIVE, RunOutcome.INCONCLUSIVE, AnalysisTerminationReason.PREREQUISITE_MISSING));
    }

    @Test
    @DisplayName("forPlanning maps BUDGET_EXHAUSTED to an inconclusive conclusion with the matching reason")
    void forPlanningMapsBudgetExhausted() {
        AnalysisConclusion conclusion = AnalysisConclusionMapper.forPlanning(PlanningStatus.BUDGET_EXHAUSTED);

        assertThat(conclusion).isEqualTo(new AnalysisConclusion(
                AttemptOutcome.INCONCLUSIVE, RunOutcome.INCONCLUSIVE, AnalysisTerminationReason.BUDGET_EXHAUSTED));
    }

    @Test
    @DisplayName("forPlanning maps AMBIGUOUS_CAPABILITY to a semantically ambiguous conclusion")
    void forPlanningMapsAmbiguousCapability() {
        AnalysisConclusion conclusion = AnalysisConclusionMapper.forPlanning(PlanningStatus.AMBIGUOUS_CAPABILITY);

        assertThat(conclusion).isEqualTo(new AnalysisConclusion(
                AttemptOutcome.INCONCLUSIVE, RunOutcome.INCONCLUSIVE, AnalysisTerminationReason.SEMANTIC_AMBIGUOUS));
    }

    @Test
    @DisplayName("forPlanning rejects PLANNED because a planned capability must not be concluded as blocked")
    void forPlanningRejectsPlanned() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> AnalysisConclusionMapper.forPlanning(PlanningStatus.PLANNED));

        assertThat(exception).hasMessage("planned capability must not be concluded as blocked");
    }

    @ParameterizedTest
    @DisplayName("forSemanticFailure with a BLOCKED outcome maps every semantic failure code to an inconclusive conclusion with the matching reason")
    @EnumSource(SemanticFailureCode.class)
    void forSemanticFailureBlockedMapsEveryFailureCode(SemanticFailureCode code) {
        AnalysisConclusion conclusion = AnalysisConclusionMapper.forSemanticFailure(
                SemanticStepOutcome.BLOCKED, semanticFailure(code));

        assertThat(conclusion).isEqualTo(new AnalysisConclusion(
                AttemptOutcome.INCONCLUSIVE, RunOutcome.INCONCLUSIVE, expectedReasonFor(code)));
    }

    @Test
    @DisplayName("forSemanticFailure with a FAILED outcome escalates the attempt/run outcome but keeps the code's reason")
    void forSemanticFailureFailedEscalatesOutcome() {
        AnalysisConclusion conclusion = AnalysisConclusionMapper.forSemanticFailure(
                SemanticStepOutcome.FAILED, semanticFailure(SemanticFailureCode.PROTOCOL_ERROR));

        assertThat(conclusion).isEqualTo(new AnalysisConclusion(
                AttemptOutcome.FAILED, RunOutcome.FAILED, AnalysisTerminationReason.SEMANTIC_UNAVAILABLE));
    }

    @Test
    @DisplayName("forSemanticFailure with a FAILED outcome and an ambiguous target keeps the ambiguous reason")
    void forSemanticFailureFailedKeepsAmbiguousReason() {
        AnalysisConclusion conclusion = AnalysisConclusionMapper.forSemanticFailure(
                SemanticStepOutcome.FAILED, semanticFailure(SemanticFailureCode.AMBIGUOUS_TARGET));

        assertThat(conclusion).isEqualTo(new AnalysisConclusion(
                AttemptOutcome.FAILED, RunOutcome.FAILED, AnalysisTerminationReason.SEMANTIC_AMBIGUOUS));
    }

    @Test
    @DisplayName("forPreparationFailure maps PROTOCOL_ERROR to a failed conclusion")
    void forPreparationFailureMapsProtocolErrorToFailed() {
        AnalysisConclusion conclusion = AnalysisConclusionMapper.forPreparationFailure(
                preparationException(SemanticFailureCode.PROTOCOL_ERROR));

        assertThat(conclusion).isEqualTo(new AnalysisConclusion(
                AttemptOutcome.FAILED, RunOutcome.FAILED, AnalysisTerminationReason.SEMANTIC_UNAVAILABLE));
    }

    @Test
    @DisplayName("forPreparationFailure maps ENGINE_FAILURE to a failed conclusion")
    void forPreparationFailureMapsEngineFailureToFailed() {
        AnalysisConclusion conclusion = AnalysisConclusionMapper.forPreparationFailure(
                preparationException(SemanticFailureCode.ENGINE_FAILURE));

        assertThat(conclusion).isEqualTo(new AnalysisConclusion(
                AttemptOutcome.FAILED, RunOutcome.FAILED, AnalysisTerminationReason.SEMANTIC_UNAVAILABLE));
    }

    @Test
    @DisplayName("forPreparationFailure maps every other semantic failure code to a blocked, inconclusive conclusion")
    void forPreparationFailureMapsOtherCodesToBlocked() {
        AnalysisConclusion ambiguous = AnalysisConclusionMapper.forPreparationFailure(
                preparationException(SemanticFailureCode.AMBIGUOUS_TARGET));
        AnalysisConclusion forbidden = AnalysisConclusionMapper.forPreparationFailure(
                preparationException(SemanticFailureCode.FORBIDDEN));
        AnalysisConclusion capabilityMissing = AnalysisConclusionMapper.forPreparationFailure(
                preparationException(SemanticFailureCode.CAPABILITY_MISSING));
        AnalysisConclusion unavailable = AnalysisConclusionMapper.forPreparationFailure(
                preparationException(SemanticFailureCode.ENGINE_UNAVAILABLE));

        assertThat(ambiguous).isEqualTo(new AnalysisConclusion(
                AttemptOutcome.INCONCLUSIVE, RunOutcome.INCONCLUSIVE, AnalysisTerminationReason.SEMANTIC_AMBIGUOUS));
        assertThat(forbidden).isEqualTo(new AnalysisConclusion(
                AttemptOutcome.INCONCLUSIVE, RunOutcome.INCONCLUSIVE, AnalysisTerminationReason.SEMANTIC_FORBIDDEN));
        assertThat(capabilityMissing).isEqualTo(new AnalysisConclusion(
                AttemptOutcome.INCONCLUSIVE, RunOutcome.INCONCLUSIVE, AnalysisTerminationReason.CAPABILITY_MISSING));
        assertThat(unavailable).isEqualTo(new AnalysisConclusion(
                AttemptOutcome.INCONCLUSIVE, RunOutcome.INCONCLUSIVE, AnalysisTerminationReason.SEMANTIC_UNAVAILABLE));
    }

    private static AnalysisTerminationReason expectedReasonFor(SemanticFailureCode code) {
        return switch (code) {
            case AMBIGUOUS_TARGET -> AnalysisTerminationReason.SEMANTIC_AMBIGUOUS;
            case FORBIDDEN -> AnalysisTerminationReason.SEMANTIC_FORBIDDEN;
            case CAPABILITY_MISSING -> AnalysisTerminationReason.CAPABILITY_MISSING;
            case NOT_READY, TIMEOUT, REPOSITORY_NOT_FOUND, PROTOCOL_ERROR,
                    ENGINE_UNAVAILABLE, ENGINE_FAILURE, PARTIAL_RESULT, REVISION_MISMATCH ->
                    AnalysisTerminationReason.SEMANTIC_UNAVAILABLE;
        };
    }

    private GoalEvaluation goalEvaluation(RunOutcome outcome) {
        return new GoalEvaluation(GoalEvaluationStatus.TERMINAL, Optional.of(outcome), "test goal evaluation");
    }

    private SemanticFailure semanticFailure(SemanticFailureCode code) {
        return new SemanticFailure(code, "semantic failure for " + code, false);
    }

    private AttemptPreparationException preparationException(SemanticFailureCode code) {
        return new AttemptPreparationException(lifecycle(), semanticFailure(code));
    }

    private AttemptLifecycle lifecycle() {
        AnalysisAttempt attempt = AnalysisAttempt.start(ATTEMPT_ID, RevisionVector.empty(), BUDGET);
        AnalysisRun run = AnalysisRun.start(RUN_ID, attempt);
        AttemptState state = AttemptState.initial(RUN_ID, ATTEMPT_ID, BUDGET);
        return new AttemptLifecycle(run, state, 0);
    }
}
