package com.java.system.agent.runtime.application;

import com.java.system.agent.runtime.application.goal.GoalEvaluation;
import com.java.system.agent.runtime.application.lifecycle.AttemptPreparationException;
import com.java.system.agent.runtime.application.planning.PlanningStatus;
import com.java.system.agent.runtime.application.semantic.SemanticStepOutcome;
import com.java.system.agent.runtime.domain.run.AttemptOutcome;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.port.in.AnalysisTerminationReason;
import com.java.system.agent.runtime.port.out.SemanticFailure;

import java.util.Objects;

/**
 * {@link BoundedAnalysisLoop} 收斂一次 run 前，把終止條件轉譯成
 * {@link AttemptOutcome}／{@link RunOutcome}／{@link AnalysisTerminationReason} 三元組的查表邏輯
 *
 * <p>四個 {@code forXxx} 方法各自對應迴圈判斷該如何收斂的四種來源：goal 判定為終局、
 * planning 卡在非 {@code PLANNED} 狀態、語意查詢結果不可重試地失敗，以及準備 attempt
 * 階段就取得失敗結果；抽出成獨立類別讓這張表本身可以被直接測試，不必透過整個迴圈間接驗證</p>
 *
 * <p>只做查表與選值，不寫入任何狀態；實際把收斂結果提交出去仍是
 * {@link BoundedAnalysisLoop} 的 {@code conclude} 方法的職責</p>
 */
final class AnalysisConclusionMapper {

    private AnalysisConclusionMapper() {
    }

    /**
     * 把 goal 已判定為終局的 {@link GoalEvaluation} 轉譯成收斂三元組
     *
     * <p>只依據 {@code evaluation.outcome()} 這個 {@link RunOutcome}，
     * {@code INCONCLUSIVE} 一律對應 {@code NO_PROGRESS}，不會再細分成其他原因</p>
     */
    static AnalysisConclusion forGoal(GoalEvaluation evaluation) {
        RunOutcome outcome = evaluation.outcome().orElseThrow();
        return switch (outcome) {
            case COMPLETED -> new AnalysisConclusion(
                    AttemptOutcome.COMPLETED, outcome, AnalysisTerminationReason.GOAL_COMPLETED);
            case INCONCLUSIVE -> new AnalysisConclusion(
                    AttemptOutcome.INCONCLUSIVE, outcome, AnalysisTerminationReason.NO_PROGRESS);
            case FAILED -> new AnalysisConclusion(
                    AttemptOutcome.FAILED, outcome, AnalysisTerminationReason.RUNTIME_FAILURE);
            case CANCELLED -> new AnalysisConclusion(
                    AttemptOutcome.CANCELLED, outcome, AnalysisTerminationReason.CANCELLED);
        };
    }

    /**
     * 把非 {@code PLANNED} 的 {@link PlanningStatus} 轉譯成收斂三元組
     *
     * <p>四個受阻狀態一律以 {@code INCONCLUSIVE} 收斂，差異只在
     * {@link AnalysisTerminationReason}；{@code PLANNED} 代表規劃成功，
     * 不應該走到收斂路徑，呼叫方式錯誤時直接拋出例外</p>
     */
    static AnalysisConclusion forPlanning(PlanningStatus status) {
        AnalysisTerminationReason reason = switch (status) {
            case CAPABILITY_MISSING -> AnalysisTerminationReason.CAPABILITY_MISSING;
            case PREREQUISITE_MISSING -> AnalysisTerminationReason.PREREQUISITE_MISSING;
            case BUDGET_EXHAUSTED -> AnalysisTerminationReason.BUDGET_EXHAUSTED;
            case AMBIGUOUS_CAPABILITY -> AnalysisTerminationReason.SEMANTIC_AMBIGUOUS;
            case PLANNED -> throw new IllegalArgumentException("planned capability must not be concluded as blocked");
        };
        return new AnalysisConclusion(AttemptOutcome.INCONCLUSIVE, RunOutcome.INCONCLUSIVE, reason);
    }

    /**
     * 把一次不可重試的語意查詢失敗轉譯成收斂三元組
     *
     * <p>{@link AnalysisTerminationReason} 完全由 {@code failure.code()} 決定；
     * {@code outcome} 只用來決定要不要把結果升級成 {@code FAILED}——{@code FAILED}
     * 對應協定層級的硬性失敗，其餘（例如 {@code BLOCKED}）一律維持
     * {@code INCONCLUSIVE}，兩者共用同一組原因對照表</p>
     */
    static AnalysisConclusion forSemanticFailure(SemanticStepOutcome outcome, SemanticFailure failure) {
        Objects.requireNonNull(failure, "semantic failure must not be null");
        AnalysisTerminationReason reason = switch (failure.code()) {
            case AMBIGUOUS_TARGET -> AnalysisTerminationReason.SEMANTIC_AMBIGUOUS;
            case FORBIDDEN -> AnalysisTerminationReason.SEMANTIC_FORBIDDEN;
            case CAPABILITY_MISSING -> AnalysisTerminationReason.CAPABILITY_MISSING;
            case NOT_READY, TIMEOUT, REPOSITORY_NOT_FOUND, PROTOCOL_ERROR,
                    ENGINE_UNAVAILABLE, ENGINE_FAILURE, PARTIAL_RESULT, REVISION_MISMATCH ->
                    AnalysisTerminationReason.SEMANTIC_UNAVAILABLE;
        };
        if (outcome == SemanticStepOutcome.FAILED) {
            return new AnalysisConclusion(AttemptOutcome.FAILED, RunOutcome.FAILED, reason);
        }
        return new AnalysisConclusion(AttemptOutcome.INCONCLUSIVE, RunOutcome.INCONCLUSIVE, reason);
    }

    /**
     * 把準備 attempt 階段就取得的失敗結果轉譯成收斂三元組
     *
     * <p>先依 {@code exception.semanticFailure().code()} 決定要以
     * {@link SemanticStepOutcome#FAILED} 還是 {@link SemanticStepOutcome#BLOCKED}
     * 呈現，再委派給 {@link #forSemanticFailure} 套用同一張原因對照表，
     * 確保準備階段與執行階段的語意失敗共用一致的收斂邏輯</p>
     */
    static AnalysisConclusion forPreparationFailure(AttemptPreparationException exception) {
        SemanticFailure failure = exception.semanticFailure();
        SemanticStepOutcome disposition = switch (failure.code()) {
            case PROTOCOL_ERROR, ENGINE_FAILURE -> SemanticStepOutcome.FAILED;
            default -> SemanticStepOutcome.BLOCKED;
        };
        return forSemanticFailure(disposition, failure);
    }
}
