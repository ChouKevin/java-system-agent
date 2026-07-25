package com.java.system.agent.runtime.domain.answer;

import java.util.Objects;

/**
 * {@link Claim} 的識別碼
 */
public record ClaimId(String value) {

    public ClaimId {
        Objects.requireNonNull(value, "claim ID must not be null");
        value = value.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("claim ID must not be blank");
        }
    }
}
