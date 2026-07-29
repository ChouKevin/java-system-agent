package com.java.system.agent.interaction.domain.delivery;

/**
 * 已認領 delivery 一次處理後的 durable 結果
 */
public enum DeliveryProcessingOutcome {
    DELIVERED,
    RETRY_SCHEDULED,
    BLOCKED
}
