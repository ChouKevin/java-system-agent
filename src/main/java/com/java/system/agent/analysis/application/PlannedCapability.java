package com.java.system.agent.analysis.application;

import com.java.system.agent.analysis.domain.InformationNeed;
import com.java.system.agent.analysis.domain.RepositoryId;
import com.java.system.agent.analysis.domain.RepositoryRevision;
import com.java.system.agent.analysis.domain.SemanticTarget;

import java.util.Objects;
import java.util.Optional;

public record PlannedCapability(
        SemanticCapability capability,
        InformationNeed informationNeed,
        RepositoryId repositoryId,
        RepositoryRevision expectedRevision,
        Optional<SemanticTarget> semanticTarget) {

    public PlannedCapability {
        Objects.requireNonNull(capability, "semantic capability must not be null");
        Objects.requireNonNull(informationNeed, "information need must not be null");
        Objects.requireNonNull(repositoryId, "repository ID must not be null");
        Objects.requireNonNull(expectedRevision, "expected repository revision must not be null");
        Objects.requireNonNull(semanticTarget, "semantic target must not be null");
    }
}
