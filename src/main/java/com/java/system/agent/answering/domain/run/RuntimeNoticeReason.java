package com.java.system.agent.answering.domain.run;

/**
 * answering 在不呼叫模型時結束分析所使用的固定通知原因
 */
public enum RuntimeNoticeReason {
    INSUFFICIENT_VERIFIABLE_INFORMATION,
    AGENT_STEP_BUDGET_EXHAUSTED,
    QUERY_EXECUTION_BUDGET_EXHAUSTED,
    ACTION_REJECTION_BUDGET_EXHAUSTED
}
