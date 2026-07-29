package com.java.system.agent.interaction.domain;

/**
 * Durable inbox 訊息目前可執行或終止的狀態
 */
public enum InboxMessageStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}
