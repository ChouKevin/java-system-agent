package com.java.system.agent.runtime.domain;

import java.util.Objects;

public record AnalysisWarning(String code, String message) {

    public AnalysisWarning {
        Objects.requireNonNull(code, "analysis warning code must not be null");
        Objects.requireNonNull(message, "analysis warning message must not be null");
        code = code.trim();
        message = message.trim();
        if (code.isBlank() || message.isBlank()) {
            throw new IllegalArgumentException("analysis warning code and message must not be blank");
        }
    }
}
