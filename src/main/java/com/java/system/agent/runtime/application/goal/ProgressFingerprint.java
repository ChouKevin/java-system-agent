package com.java.system.agent.runtime.application.goal;

import com.java.system.agent.runtime.domain.run.AttemptState;
import com.java.system.agent.runtime.domain.run.AttemptStatus;
import com.java.system.agent.runtime.domain.evidence.ArtifactRef;
import com.java.system.agent.runtime.domain.need.InformationNeedId;
import com.java.system.agent.runtime.domain.scope.RepositoryId;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 從 {@link AttemptState} 抽出的一組可比較快照，代表這個 attempt 目前的進展
 *
 * <p>比較四個欄位：已納入 scope 的 repository 清單、已解決的 information need ID
 * 集合、已接受證據對應的 artifact 清單，以及（若已終結）終結狀態；
 * {@link NoProgressPolicy} 靠連續幾輪指紋是否完全相同來判斷有沒有停滯</p>
 *
 * <p>由 {@link #from(AttemptState)} 在每一輪語意查詢結束後建立一份快照</p>
 */
public record ProgressFingerprint(
        List<RepositoryId> scopedRepositories,
        Set<InformationNeedId> resolvedNeedIds,
        List<ArtifactRef> evidenceArtifacts,
        Optional<AttemptStatus> terminalStatus) {

    public ProgressFingerprint {
        Objects.requireNonNull(scopedRepositories, "scoped repositories must not be null");
        Objects.requireNonNull(resolvedNeedIds, "resolved need IDs must not be null");
        Objects.requireNonNull(evidenceArtifacts, "evidence artifacts must not be null");
        Objects.requireNonNull(terminalStatus, "terminal analysis status must not be null");
        scopedRepositories = List.copyOf(scopedRepositories);
        resolvedNeedIds = Collections.unmodifiableSet(new TreeSet<>(resolvedNeedIds));
        evidenceArtifacts = List.copyOf(evidenceArtifacts);
    }

    public static ProgressFingerprint from(AttemptState state) {
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

    private static Optional<AttemptStatus> terminalStatus(AttemptStatus status) {
        return switch (status) {
            case STALE, COMPLETED, INCONCLUSIVE, FAILED, CANCELLED -> Optional.of(status);
            default -> Optional.empty();
        };
    }
}
