package com.java.system.agent.runtime.domain.evidence;

import java.util.Objects;

/**
 * 附著在單筆 {@link EvidenceRef} 上的品質警示
 *
 * <p>與 {@code run} package 的 {@code AnalysisWarning} 不同層級：這裡只描述單筆證據本身的疑慮，
 * 不是整個 Attempt 的警示</p>
 */
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
