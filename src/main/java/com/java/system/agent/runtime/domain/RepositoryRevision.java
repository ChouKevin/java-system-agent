package com.java.system.agent.runtime.domain;

import java.util.Objects;

public record RepositoryRevision(String value) {

    public RepositoryRevision {
        Objects.requireNonNull(value, "repository revision must not be null");
        value = value.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("repository revision must not be blank");
        }
    }
}
