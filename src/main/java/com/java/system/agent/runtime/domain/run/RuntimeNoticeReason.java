package com.java.system.agent.runtime.domain.run;

/**
 * runtime 在不呼叫模型時結束分析所使用的固定通知原因
 */
public enum RuntimeNoticeReason {
    INSUFFICIENT_VERIFIABLE_INFORMATION,
    PLANNING_BUDGET_EXHAUSTED
}
