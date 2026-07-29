package com.java.system.agent.answering.domain.observation;

import java.util.Objects;

/**
 * 一筆觀察結果的 answering 識別碼
 */
public record ObservationId(String value) {
    public ObservationId {
        Objects.requireNonNull(value, "observation ID must not be null");
        value = value.trim();
        if (value.isBlank()) throw new IllegalArgumentException("observation ID must not be blank");
    }
}
