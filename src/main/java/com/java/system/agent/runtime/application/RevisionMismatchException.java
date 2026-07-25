package com.java.system.agent.runtime.application;

import com.java.system.agent.runtime.domain.RepositoryId;
import com.java.system.agent.runtime.domain.RepositoryRevision;

public class RevisionMismatchException extends IllegalArgumentException {

    public RevisionMismatchException(
            RepositoryId repositoryId,
            RepositoryRevision analyzedRevision) {
        super("evidence revision does not match the pinned revision for %s: %s"
                .formatted(repositoryId.value(), analyzedRevision.value()));
    }
}
