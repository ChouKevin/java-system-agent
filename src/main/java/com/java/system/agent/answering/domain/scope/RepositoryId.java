package com.java.system.agent.answering.domain.scope;

import java.util.Objects;

/**
 * repository 的識別碼
 *
 * <p>可排序，供 {@link RevisionVector} 內部維持穩定順序</p>
 */
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
