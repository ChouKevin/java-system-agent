package com.java.system.agent.analysis.application;

import com.java.system.agent.analysis.domain.AnalysisState;
import com.java.system.agent.analysis.domain.AnalysisStatus;
import com.java.system.agent.analysis.domain.ArtifactRef;
import com.java.system.agent.analysis.domain.InformationNeedId;
import com.java.system.agent.analysis.domain.RepositoryId;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public record ProgressFingerprint(
        List<RepositoryId> scopedRepositories,
        Set<InformationNeedId> resolvedNeedIds,
        List<ArtifactRef> evidenceArtifacts,
        Optional<AnalysisStatus> terminalStatus) {

    public ProgressFingerprint {
        Objects.requireNonNull(scopedRepositories, "scoped repositories must not be null");
        Objects.requireNonNull(resolvedNeedIds, "resolved need IDs must not be null");
        Objects.requireNonNull(evidenceArtifacts, "evidence artifacts must not be null");
        Objects.requireNonNull(terminalStatus, "terminal analysis status must not be null");
        scopedRepositories = List.copyOf(scopedRepositories);
        resolvedNeedIds = Collections.unmodifiableSet(new TreeSet<>(resolvedNeedIds));
        evidenceArtifacts = List.copyOf(evidenceArtifacts);
    }

    public static ProgressFingerprint from(AnalysisState state) {
        Objects.requireNonNull(state, "analysis state must not be null");
        List<ArtifactRef> artifacts = state.evidenceBindings().stream()
                .map(binding -> binding.evidenceRef().artifactRef())
                .distinct()
                .sorted((left, right) -> left.digest().compareTo(right.digest()))
                .toList();
        return new ProgressFingerprint(
                state.repositoryScope().repositoryIds(),
                state.resolvedNeedIds(),
                artifacts,
                terminalStatus(state.status()));
    }

    private static Optional<AnalysisStatus> terminalStatus(AnalysisStatus status) {
        return switch (status) {
            case STALE, COMPLETED, INCONCLUSIVE, FAILED, CANCELLED -> Optional.of(status);
            default -> Optional.empty();
        };
    }
}
