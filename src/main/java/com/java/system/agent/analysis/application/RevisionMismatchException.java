package com.java.system.agent.analysis.application;

import com.java.system.agent.analysis.domain.RepositoryId;
import com.java.system.agent.analysis.domain.RepositoryRevision;

public class RevisionMismatchException extends IllegalArgumentException {

    public RevisionMismatchException(
            RepositoryId repositoryId,
            RepositoryRevision analyzedRevision) {
        super("evidence revision does not match the pinned revision for %s: %s"
                .formatted(repositoryId.value(), analyzedRevision.value()));
    }
}
