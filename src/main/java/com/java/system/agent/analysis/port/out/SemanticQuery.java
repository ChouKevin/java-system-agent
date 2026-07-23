package com.java.system.agent.analysis.port.out;

import com.java.system.agent.analysis.domain.InformationNeed;
import com.java.system.agent.analysis.domain.RepositoryId;
import com.java.system.agent.analysis.domain.RepositoryRevision;
import com.java.system.agent.analysis.domain.SemanticTarget;

import java.util.Objects;

public record SemanticQuery(
        String capabilityName,
        InformationNeed informationNeed,
        SemanticTarget semanticTarget,
        RepositoryId repositoryId,
        RepositoryRevision expectedRevision) {

    public SemanticQuery {
        Objects.requireNonNull(capabilityName, "semantic capability name must not be null");
        Objects.requireNonNull(informationNeed, "information need must not be null");
        Objects.requireNonNull(semanticTarget, "semantic target must not be null");
        Objects.requireNonNull(repositoryId, "repository ID must not be null");
        Objects.requireNonNull(expectedRevision, "expected repository revision must not be null");
        capabilityName = capabilityName.trim();
        if (capabilityName.isBlank()) {
            throw new IllegalArgumentException("semantic capability name must not be blank");
        }
    }
}
