package com.java.system.agent.inbox.domain;

/**
 * 已認領 inbox 訊息一次處理後的 durable 結果
 */
public enum InboxProcessingOutcome {
    COMPLETED,
    CAPACITY_DEFERRED,
    RETRY_SCHEDULED,
    FAILED
}
