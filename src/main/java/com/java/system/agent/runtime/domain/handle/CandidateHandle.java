package com.java.system.agent.runtime.domain.handle;

import com.java.system.agent.runtime.domain.candidate.CandidateKind;

import java.util.Objects;

/**
 * Runtime 配發給分析候選項目的不透明代號
 */
public record CandidateHandle(String value, HandleBinding binding, CandidateKind kind) {

    public CandidateHandle {
        Objects.requireNonNull(value, "candidate handle value must not be null");
        Objects.requireNonNull(binding, "candidate handle binding must not be null");
        Objects.requireNonNull(kind, "candidate handle kind must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("candidate handle value must not be blank");
        }
    }
}
