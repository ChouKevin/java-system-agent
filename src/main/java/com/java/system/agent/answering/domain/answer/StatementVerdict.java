package com.java.system.agent.answering.domain.answer;

import java.util.Objects;

/**
 * 對一個回答 statement 的驗證結果
 */
public record StatementVerdict(StatementId statementId, StatementVerdictStatus status, String description) {
    public StatementVerdict {
        Objects.requireNonNull(statementId, "statement verdict statement ID must not be null");
        Objects.requireNonNull(status, "statement verdict status must not be null");
        Objects.requireNonNull(description, "statement verdict description must not be null");
        if (description.isBlank()) throw new IllegalArgumentException("statement verdict description must not be blank");
    }
}
