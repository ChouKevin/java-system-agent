package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.scope.RepositoryRevision;

import java.util.Objects;
import java.util.Optional;

public record RepositoryRevisionResult(
        Optional<RepositoryRevision> revision,
        Optional<SemanticFailure> failure) {

    public RepositoryRevisionResult {
        Objects.requireNonNull(revision, "repository revision result revision must not be null");
        Objects.requireNonNull(failure, "repository revision result failure must not be null");
        if (revision.isPresent() == failure.isPresent()) {
            throw new IllegalArgumentException(
                    "repository revision result must contain exactly one of revision or failure");
        }
    }

    public static RepositoryRevisionResult ready(RepositoryRevision revision) {
        Objects.requireNonNull(revision, "repository revision must not be null");
        return new RepositoryRevisionResult(Optional.of(revision), Optional.empty());
    }

    public static RepositoryRevisionResult unavailable(SemanticFailure failure) {
        Objects.requireNonNull(failure, "repository revision failure must not be null");
        return new RepositoryRevisionResult(Optional.empty(), Optional.of(failure));
    }
}
