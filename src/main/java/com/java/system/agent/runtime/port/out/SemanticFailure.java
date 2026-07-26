package com.java.system.agent.runtime.port.out;

import java.util.Objects;

/**
 * Repository revision 或 Agent semantic query 外部邊界回傳的結構化失敗
 */
public record SemanticFailure(
        SemanticFailureCode code,
        String message,
        boolean retryable) {

    public SemanticFailure {
        Objects.requireNonNull(code, "semantic failure code must not be null");
        Objects.requireNonNull(message, "semantic failure message must not be null");
        message = message.trim();
        if (message.isBlank()) {
            throw new IllegalArgumentException("semantic failure message must not be blank");
        }
    }
}
