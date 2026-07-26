package com.java.system.agent.runtime.port.in;

/**
 * Inbox 對同一分析 run 的 durable 執行階段宣告
 */
public enum AnswerExecutionMode {
    INITIAL,
    RETRY,
    TERMINAL_RECONCILIATION;

    /**
     * 驗證 durable inbox attempt 與執行模式的一致性
     */
    public void validateAttempt(int executionAttempt) {
        if (executionAttempt < 1) {
            throw new IllegalArgumentException("answer execution attempt must be positive");
        }
        if (this == INITIAL && executionAttempt != 1) {
            throw new IllegalArgumentException("initial answer execution requires attempt one");
        }
        if (this != INITIAL && executionAttempt == 1) {
            throw new IllegalArgumentException("answer recovery execution requires an attempt after one");
        }
    }
}
