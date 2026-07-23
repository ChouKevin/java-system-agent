package com.java.system.agent.analysis.domain;

import java.util.Objects;

public record InformationNeedId(String value) implements Comparable<InformationNeedId> {

    public InformationNeedId {
        Objects.requireNonNull(value, "information need ID must not be null");
        value = value.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("information need ID must not be blank");
        }
    }

    @Override
    public int compareTo(InformationNeedId other) {
        Objects.requireNonNull(other, "information need ID must not be null");
        return value.compareTo(other.value);
    }
}
