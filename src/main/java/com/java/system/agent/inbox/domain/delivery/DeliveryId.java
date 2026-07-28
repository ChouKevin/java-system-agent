package com.java.system.agent.inbox.domain.delivery;

import java.util.Objects;

/**
 * Durable delivery outbox 訊息的 opaque 識別碼
 */
public record DeliveryId(String value) {

    public DeliveryId {
        value = requiredOpaqueValue(value, "delivery ID");
    }

    static String requiredOpaqueValue(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        String trimmedValue = value.trim();
        if (trimmedValue.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return trimmedValue;
    }
}
