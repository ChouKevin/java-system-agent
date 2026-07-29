package com.java.system.agent.interaction.domain.delivery;

/**
 * Durable delivery outbox 的處理狀態
 */
public enum DeliveryStatus {
    WAITING_FOR_RECEIPT,
    PENDING,
    PROCESSING,
    RETRY_SCHEDULED,
    DELIVERED,
    BLOCKED
}
