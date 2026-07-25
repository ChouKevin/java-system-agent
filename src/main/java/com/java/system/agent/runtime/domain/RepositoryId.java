package com.java.system.agent.runtime.domain;

import java.util.Objects;

public record RepositoryId(String value) implements Comparable<RepositoryId> {

    public RepositoryId {
        Objects.requireNonNull(value, "repository ID must not be null");
        value = value.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("repository ID must not be blank");
        }
    }

    @Override
    public int compareTo(RepositoryId other) {
        Objects.requireNonNull(other, "repository ID must not be null");
        return value.compareTo(other.value);
    }
}
