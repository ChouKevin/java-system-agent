package com.java.system.agent.runtime.domain.answer;

import java.util.Objects;

/**
 * 一份新型回答文件中 statement 的識別碼
 */
public record StatementId(String value) {
    public StatementId {
        Objects.requireNonNull(value, "statement ID must not be null"); value = value.trim();
        if (value.isBlank()) throw new IllegalArgumentException("statement ID must not be blank");
    }
}
