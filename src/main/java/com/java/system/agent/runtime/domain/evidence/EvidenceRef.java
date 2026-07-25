package com.java.system.agent.runtime.domain.evidence;

import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;

import java.util.List;
import java.util.Objects;

/**
 * 語意服務回應中，一筆可佐證答案的證據
 *
 * <p>由 semantic 層將 {@code SemanticQueryResult} 轉譯而來，經 state 層核准後
 * 才會綁進 {@code EvidenceBinding}</p>
 *
 * <p>{@code repositoryId}／{@code repositoryRevision} 必須與當時的 {@code RevisionVector} 相符，
 * 否則視為過期證據</p>
 */
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
