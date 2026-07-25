package com.java.system.agent.runtime.application.planning;

/**
 * {@link InformationNeedPlanner#plan} 的判定結果
 *
 * <p>只有 {@code PLANNED} 能繼續往語意查詢階段前進；其餘四個值各自對應一種讓
 * {@link com.java.system.agent.runtime.application.BoundedAnalysisLoop} 終止 run 的原因：
 * 前提不足、能力缺漏、能力歧義或預算耗盡</p>
 */
public enum PlanningStatus {
    PLANNED,
    PREREQUISITE_MISSING,
    CAPABILITY_MISSING,
    AMBIGUOUS_CAPABILITY,
    BUDGET_EXHAUSTED
}
