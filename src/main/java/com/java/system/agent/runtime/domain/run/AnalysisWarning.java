package com.java.system.agent.runtime.domain.run;

import java.util.Objects;

/**
 * 分析過程中未阻斷執行、但值得回報的異常狀況
 *
 * <p>累積於 {@link AttemptState#warnings()}，最終隨結果一併呈現給使用者</p>
 */
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
