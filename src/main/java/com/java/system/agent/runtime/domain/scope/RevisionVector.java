package com.java.system.agent.runtime.domain.scope;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 一次 Attempt 內，每個 repository 各自釘選的 revision 集合
 *
 * <p>runtime 只會為已驗證、由模型選定的 repository 直接釘選 revision，供全程比對是否過期</p>
 *
 * <p>{@link #pin(RepositoryId, RepositoryRevision)} 只允許為尚未釘選的 repository 第一次釘選；
 * 已釘選過的 repository 若傳入不同 revision 會直接丟例外，同一 Attempt 內既有 repository 的
 * revision 不可被取代</p>
 */
public final class RevisionVector {

    private final SortedMap<RepositoryId, RepositoryRevision> revisions;

    private RevisionVector(SortedMap<RepositoryId, RepositoryRevision> revisions) {
        this.revisions = Collections.unmodifiableSortedMap(new TreeMap<>(revisions));
    }

    public static RevisionVector empty() {
        return new RevisionVector(new TreeMap<>());
    }

    public RevisionVector pin(RepositoryId repositoryId, RepositoryRevision revision) {
        Objects.requireNonNull(repositoryId, "repository ID must not be null");
        Objects.requireNonNull(revision, "repository revision must not be null");
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

    /**
     * 找出相對於 {@code previous} 發生 revision 變動的 repository
     *
     * <p>只有同時存在於兩個 vector 的 repository 才可能被判定為 drift——只存在於
     * 這一份、previous 沒有的 repository，代表它是這一輪才首次釘選，並非變動；只存在於
     * previous、這一份沒有的 repository，代表它這一輪未被選用，同樣不算變動</p>
     */
    public Set<RepositoryId> driftedFrom(RevisionVector previous) {
        Objects.requireNonNull(previous, "previous revision vector must not be null");
        Set<RepositoryId> drifted = new TreeSet<>();
        for (Map.Entry<RepositoryId, RepositoryRevision> pinned : revisions.entrySet()) {
            Optional<RepositoryRevision> previousRevision = previous.revisionOf(pinned.getKey());
            if (previousRevision.isPresent() && !previousRevision.orElseThrow().equals(pinned.getValue())) {
                drifted.add(pinned.getKey());
            }
        }
        return Collections.unmodifiableSet(drifted);
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
