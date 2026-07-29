package com.java.system.agent.answering.port.out;

import com.java.system.agent.answering.domain.scope.RepositoryId;

import java.util.Objects;

/**
 * 一個可由模型從 answering-issued handles 選擇的 repository 候選項
 *
 * <p>{@code description} 是給模型理解 repository candidate 的簡短說明，
 * 不是完整的知識文件</p>
 */
public record RepositoryDescriptor(RepositoryId repositoryId, String description) {

    public RepositoryDescriptor {
        Objects.requireNonNull(repositoryId, "repository ID must not be null");
        Objects.requireNonNull(description, "repository description must not be null");
    }
}
