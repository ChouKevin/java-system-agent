package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.scope.RepositoryId;

import java.util.Objects;

/**
 * 一個可供理解問題階段挑選的 repository 候選項
 *
 * <p>{@code description} 是給 {@link QuestionUnderstandingPort} 判斷相關性用的簡短說明，
 * 不是完整的知識文件</p>
 */
public record RepositoryDescriptor(RepositoryId repositoryId, String description) {

    public RepositoryDescriptor {
        Objects.requireNonNull(repositoryId, "repository ID must not be null");
        Objects.requireNonNull(description, "repository description must not be null");
    }
}
