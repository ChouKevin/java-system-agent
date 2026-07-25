package com.java.system.agent.runtime.domain.run;

import java.util.Objects;

/**
 * {@link AnalysisRun} 的識別碼
 *
 * <p>由呼叫端（Slack 事件或其他觸發來源）配發，貫穿一次使用者問題的所有 Attempt</p>
 */
public record AnalysisRunId(String value) {

    public AnalysisRunId {
        Objects.requireNonNull(value, "analysis run ID must not be null");
        value = value.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("analysis run ID must not be blank");
        }
    }
}
