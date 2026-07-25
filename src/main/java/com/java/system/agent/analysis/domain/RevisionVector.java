package com.java.system.agent.analysis.domain;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

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
