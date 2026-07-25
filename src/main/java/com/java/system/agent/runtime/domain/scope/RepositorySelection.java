package com.java.system.agent.runtime.domain.scope;

import java.util.Objects;

/**
 * 一個 repository 被納入分析範圍的紀錄
 *
 * <p>{@code discoverySource} 記錄它是怎麼被發現的（使用者指定、問題理解、metadata……）
 * {@code required} 標記它是否為 Goal 完成所必要，而非僅供參考</p>
 */
public record RepositorySelection(
        RepositoryId repositoryId,
        String selectionReason,
        boolean required,
        RepositoryDiscoverySource discoverySource) {

    public RepositorySelection {
        Objects.requireNonNull(repositoryId, "repository ID must not be null");
        Objects.requireNonNull(selectionReason, "selection reason must not be null");
        Objects.requireNonNull(discoverySource, "discovery source must not be null");
        selectionReason = selectionReason.trim();
        if (selectionReason.isBlank()) {
            throw new IllegalArgumentException("selection reason must not be blank");
        }
    }
}
