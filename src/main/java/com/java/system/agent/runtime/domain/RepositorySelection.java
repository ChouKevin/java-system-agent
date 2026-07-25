package com.java.system.agent.runtime.domain;

import java.util.Objects;

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
