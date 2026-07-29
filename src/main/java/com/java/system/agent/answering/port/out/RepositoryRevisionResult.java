package com.java.system.agent.answering.port.out;

import com.java.system.agent.answering.domain.scope.RepositoryRevision;

import java.util.Objects;

/**
 * Repository revision 外部查詢的成功或 provider-neutral 失敗
 */
public sealed interface RepositoryRevisionResult permits RepositoryRevisionResult.Ready,
        RepositoryRevisionResult.Failed {

    record Ready(RepositoryRevision revision) implements RepositoryRevisionResult {
        public Ready {
            Objects.requireNonNull(revision, "repository revision must not be null");
        }
    }

    record Failed(RepositoryRevisionFailure failure) implements RepositoryRevisionResult {
        public Failed {
            Objects.requireNonNull(failure, "repository revision failure must not be null");
        }
    }

    static Ready ready(RepositoryRevision revision) {
        return new Ready(revision);
    }

    static Failed failed(RepositoryRevisionFailure failure) {
        return new Failed(failure);
    }
}
