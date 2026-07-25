package com.java.system.agent.runtime.domain.answer;

import java.util.Objects;

/**
 * 主張引用證據時使用的輕量代號，例如 "E1"
 *
 * <p>與 {@link CitableEvidence} 的 binding 一一對應，本身只是可讀的引用代號，
 * 不攜帶證據內容</p>
 */
public record EvidenceHandle(String value) {

    public EvidenceHandle {
        Objects.requireNonNull(value, "evidence handle must not be null");
        value = value.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("evidence handle must not be blank");
        }
    }
}
