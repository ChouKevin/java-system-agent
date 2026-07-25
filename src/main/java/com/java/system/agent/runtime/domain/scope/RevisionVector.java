package com.java.system.agent.runtime.domain.scope;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 一次 Attempt 內，每個 repository 各自釘選的 revision 集合
 *
 * <p>由 lifecycle 在準備 attempt 與 scope 擴張時逐一釘選，供全程比對是否過期</p>
 *
 * <p>{@link #pin(RepositoryScope, RepositoryId, RepositoryRevision)} 只允許為尚未釘選的
 * repository 第一次釘選；已釘選過的 repository 若傳入不同 revision 會直接丟例外——
 * 同一 Attempt 內既有 repository 的 revision 不可被取代，只有新發現的 repository 才可能被釘選</p>
 */
public final class RevisionVector {

    private final SortedMap<RepositoryId, RepositoryRevision> revisions;

    private RevisionVector(SortedMap<RepositoryId, RepositoryRevision> revisions) {
        this.revisions = Collections.unmodifiableSortedMap(new TreeMap<>(revisions));
    }

    public static RevisionVector empty() {
        return new RevisionVector(new TreeMap<>());
    }

    public RevisionVector pin(
            RepositoryScope scope,
            RepositoryId repositoryId,
            RepositoryRevision revision) {
        Objects.requireNonNull(scope, "repository scope must not be null");
        Objects.requireNonNull(repositoryId, "repository ID must not be null");
        Objects.requireNonNull(revision, "repository revision must not be null");
        if (!scope.contains(repositoryId)) {
            throw new IllegalArgumentException(
                    "repository is outside the analysis scope: " + repositoryId.value());
        }
        RepositoryRevision pinnedRevision = revisions.get(repositoryId);
        if (Objects.nonNull(pinnedRevision) && !pinnedRevision.equals(revision)) {
            throw new IllegalArgumentException(
                    "repository revision is already pinned to " + pinnedRevision.value());
        }
        if (Objects.nonNull(pinnedRevision)) {
            return this;
        }
        SortedMap<RepositoryId, RepositoryRevision> pinned = new TreeMap<>(revisions);
        pinned.put(repositoryId, revision);
        return new RevisionVector(pinned);
    }

    public boolean matches(RepositoryId repositoryId, RepositoryRevision analyzedRevision) {
        Objects.requireNonNull(repositoryId, "repository ID must not be null");
        Objects.requireNonNull(analyzedRevision, "analyzed revision must not be null");
        return analyzedRevision.equals(revisions.get(repositoryId));
    }

    public Optional<RepositoryRevision> revisionOf(RepositoryId repositoryId) {
        Objects.requireNonNull(repositoryId, "repository ID must not be null");
        return Optional.ofNullable(revisions.get(repositoryId));
    }

    public List<RepositoryId> repositoryIds() {
        return List.copyOf(revisions.keySet());
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof RevisionVector revisionVector // cs-allow
                && revisions.equals(revisionVector.revisions);
    }

    @Override
    public int hashCode() {
        return revisions.hashCode();
    }
}
