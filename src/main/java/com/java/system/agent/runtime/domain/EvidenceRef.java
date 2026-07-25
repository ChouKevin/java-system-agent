package com.java.system.agent.runtime.domain;

import java.util.List;
import java.util.Objects;

public record EvidenceRef(
        String sourceService,
        RepositoryId repositoryId,
        RepositoryRevision repositoryRevision,
        SemanticTarget semanticTarget,
        double confidence,
        List<EvidenceWarning> warnings,
        ArtifactRef artifactRef) {

    public EvidenceRef {
        Objects.requireNonNull(sourceService, "evidence source service must not be null");
        Objects.requireNonNull(repositoryId, "evidence repository ID must not be null");
        Objects.requireNonNull(repositoryRevision, "evidence repository revision must not be null");
        Objects.requireNonNull(semanticTarget, "evidence semantic target must not be null");
        Objects.requireNonNull(warnings, "evidence warnings must not be null");
        Objects.requireNonNull(artifactRef, "evidence artifact ref must not be null");
        sourceService = sourceService.trim();
        if (sourceService.isBlank()) {
            throw new IllegalArgumentException("evidence source service must not be blank");
        }
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("evidence confidence must be between 0.0 and 1.0");
        }
        warnings = warnings.stream()
                .map(warning -> Objects.requireNonNull(warning, "evidence warning must not be null"))
                .toList();
    }
}
