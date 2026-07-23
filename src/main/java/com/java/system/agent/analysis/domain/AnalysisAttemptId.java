package com.java.system.agent.analysis.domain;

import java.util.Objects;

public record AnalysisAttemptId(String value) {

    public AnalysisAttemptId {
        Objects.requireNonNull(value, "analysis attempt ID must not be null");
        value = value.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("analysis attempt ID must not be blank");
        }
    }
}
