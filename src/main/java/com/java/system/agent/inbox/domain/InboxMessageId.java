package com.java.system.agent.inbox.domain;

import java.util.Objects;

/**
 * Durable inbox 訊息的 opaque 識別碼
 */
public record InboxMessageId(String value) {

    public InboxMessageId {
        value = requiredOpaqueValue(value, "inbox message ID");
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
