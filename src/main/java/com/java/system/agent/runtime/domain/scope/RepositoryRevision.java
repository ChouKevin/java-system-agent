package com.java.system.agent.runtime.domain.scope;

import java.util.Objects;

/**
 * 一個 repository 在某個時間點的版本座標
 *
 * <p>由 {@link RevisionVector} 綁定給對應的 {@link RepositoryId}，
 * 是整輪 Attempt 分析所依據的具體版本</p>
 */
public record RepositoryRevision(String value) {

    public RepositoryRevision {
        Objects.requireNonNull(value, "repository revision must not be null");
        value = value.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("repository revision must not be blank");
        }
    }
}
