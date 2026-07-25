package com.java.system.agent.runtime.application.goal;

/**
 * {@link GoalEvaluator#evaluate} 選用（optional）封鎖原因參數的詞彙，表示迴圈目前為什麼
 * 無法繼續推進
 *
 * <p>這是一個目前 production 尚未使用的擴充點：{@link
 * com.java.system.agent.runtime.application.BoundedAnalysisLoop} 是唯一在正式環境呼叫
 * {@code GoalEvaluator#evaluate} 的地方，永遠固定傳入 {@code Optional.empty()}，因此
 * {@link DefaultGoalEvaluator} 裡消費這個參數的兩個分支（對應 {@code INVARIANT_FAILURE}
 * 與 {@code CANCELLED}）目前只有測試會走到；{@link NoProgressPolicy} 雖然會自行算出
 * {@code NO_PROGRESS} 這個封鎖原因，但它的回傳值並未被轉送到這裡——production 真正的
 * {@code NO_PROGRESS} 終止路徑繞過本 enum，由呼叫端直接讀
 * {@code NoProgressEvaluation#terminate()} 並寫死
 * {@code AnalysisTerminationReason#NO_PROGRESS}；除了 {@code CANCELLED} 與
 * {@code INVARIANT_FAILURE} 這兩個對應 CANCELLED/FAILED 的特殊值以外，其餘四個值刻意與
 * 對外公開的 {@code AnalysisTerminationReason} 同名，方便未來由呼叫端一對一轉譯，但這條
 * 轉譯路徑尚未實際接上，讀者不應假設有這層串接</p>
 */
public enum GoalBlockReason {
    CAPABILITY_MISSING,
    PREREQUISITE_MISSING,
    BUDGET_EXHAUSTED,
    NO_PROGRESS,
    INVARIANT_FAILURE,
    CANCELLED
}
