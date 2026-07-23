package com.java.system.agent.analysis.port.out;

import com.java.system.agent.analysis.domain.EvidenceRef;
import com.java.system.agent.analysis.domain.RepositoryId;

import java.util.Objects;

public record RepositoryDiscovery(
        RepositoryId repositoryId,
        String discoveryReason,
        EvidenceRef sourceEvidence) {

    public RepositoryDiscovery {
        Objects.requireNonNull(repositoryId, "discovered repository ID must not be null");
        Objects.requireNonNull(discoveryReason, "repository discovery reason must not be null");
        Objects.requireNonNull(sourceEvidence, "repository discovery source evidence must not be null");
        discoveryReason = discoveryReason.trim();
        if (discoveryReason.isBlank()) {
            throw new IllegalArgumentException("repository discovery reason must not be blank");
        }
    }
}
