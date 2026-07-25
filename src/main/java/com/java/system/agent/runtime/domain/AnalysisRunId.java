package com.java.system.agent.runtime.domain;

import java.util.Objects;

public record AnalysisRunId(String value) {

    public AnalysisRunId {
        Objects.requireNonNull(value, "analysis run ID must not be null");
        value = value.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("analysis run ID must not be blank");
        }
    }
}
