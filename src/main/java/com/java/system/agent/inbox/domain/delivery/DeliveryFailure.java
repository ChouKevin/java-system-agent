package com.java.system.agent.inbox.domain.delivery;

import java.util.Objects;

/**
 * 可安全持久化的 delivery 失敗摘要
 */
public record DeliveryFailure(String category, String description) {

    public static final int MAX_CATEGORY_LENGTH = 64;
    public static final int MAX_DESCRIPTION_LENGTH = 512;

    public DeliveryFailure {
        category = boundedSingleLine(category, "delivery failure category", MAX_CATEGORY_LENGTH);
        description = boundedSingleLine(description, "delivery failure description", MAX_DESCRIPTION_LENGTH);
    }

    static String boundedSingleLine(String value, String fieldName, int maximumLength) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        String trimmedValue = value.trim();
        if (trimmedValue.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        if (trimmedValue.length() > maximumLength) {
            throw new IllegalArgumentException(fieldName + " exceeds " + maximumLength + " characters");
        }
        if (containsLineSeparator(trimmedValue)) {
            throw new IllegalArgumentException(fieldName + " must be a single line");
        }
        return trimmedValue;
    }

    private static boolean containsLineSeparator(String value) {
        return value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0
                || value.indexOf('\u0085') >= 0
                || value.indexOf('\u2028') >= 0
                || value.indexOf('\u2029') >= 0;
    }
}
