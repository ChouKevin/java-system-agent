package com.java.system.agent.answering.domain.run;

import java.util.Objects;

/**
 * 一次 validated action loop attempt 的識別碼
 *
 * <p>由 answering 在準備 attempt 時產生，區分同一個 run 底下的多次嘗試</p>
 */
public record AnalysisAttemptId(String value) {

    public AnalysisAttemptId {
        Objects.requireNonNull(value, "analysis attempt ID must not be null");
        value = value.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("analysis attempt ID must not be blank");
        }
    }
}
