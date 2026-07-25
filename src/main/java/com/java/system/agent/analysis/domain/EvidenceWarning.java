package com.java.system.agent.analysis.domain;

import java.util.Objects;

public record EvidenceWarning(String code, String message) {

    public EvidenceWarning {
        Objects.requireNonNull(code, "evidence warning code must not be null");
        Objects.requireNonNull(message, "evidence warning message must not be null");
        code = code.trim();
        message = message.trim();
        if (code.isBlank()) {
            throw new IllegalArgumentException("evidence warning code must not be blank");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("evidence warning message must not be blank");
        }
    }
}
