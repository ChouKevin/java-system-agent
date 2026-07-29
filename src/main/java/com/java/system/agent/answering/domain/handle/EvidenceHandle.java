package com.java.system.agent.answering.domain.handle;

import java.util.Objects;

/**
 * Runtime 配發給已接受證據的不透明代號
 */
public record EvidenceHandle(String value, HandleBinding binding) {

    public EvidenceHandle {
        Objects.requireNonNull(value, "evidence handle value must not be null");
        Objects.requireNonNull(binding, "evidence handle binding must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("evidence handle value must not be blank");
        }
    }
}
