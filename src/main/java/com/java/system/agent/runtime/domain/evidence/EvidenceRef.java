package com.java.system.agent.runtime.domain.evidence;

import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;

import java.util.List;
import java.util.Objects;

/**
 * capability 執行器回應中，一筆可佐證答案的證據
 *
 * <p>由 capability 執行器轉譯而來，經 state 層核准後
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
        String content,
        List<EvidenceWarning> warnings,
        ArtifactRef artifactRef) {

    public EvidenceRef {
        Objects.requireNonNull(sourceService, "evidence source service must not be null");
        Objects.requireNonNull(repositoryId, "evidence repository ID must not be null");
        Objects.requireNonNull(repositoryRevision, "evidence repository revision must not be null");
        Objects.requireNonNull(semanticTarget, "evidence semantic target must not be null");
        Objects.requireNonNull(content, "evidence content must not be null");
        Objects.requireNonNull(warnings, "evidence warnings must not be null");
        Objects.requireNonNull(artifactRef, "evidence artifact ref must not be null");
        sourceService = sourceService.trim();
        if (sourceService.isBlank()) {
            throw new IllegalArgumentException("evidence source service must not be blank");
        }
        if (content.isBlank()) {
            throw new IllegalArgumentException("evidence content must not be blank");
        }
        warnings = warnings.stream()
                .map(warning -> Objects.requireNonNull(warning, "evidence warning must not be null"))
                .toList();
    }
}
