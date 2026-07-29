package com.java.system.agent.answering.domain.answer;

import java.util.Objects;

/**
 * 回答文件中可驗證事實段落的宣稱識別碼
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
