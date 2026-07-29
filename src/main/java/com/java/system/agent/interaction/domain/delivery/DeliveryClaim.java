package com.java.system.agent.interaction.domain.delivery;

import java.util.Objects;

/**
 * 已原子轉為 PROCESSING 的單筆 delivery outbox 訊息
 */
public record DeliveryClaim(DeliveryMessage message) {

    public DeliveryClaim {
        Objects.requireNonNull(message, "delivery message must not be null");
        if (message.status() != DeliveryStatus.PROCESSING) {
            throw new IllegalArgumentException("delivery claim must contain a processing message");
        }
    }
}
