package com.java.system.agent.runtime.application;

import com.java.system.agent.runtime.domain.AnalysisState;
import com.java.system.agent.runtime.port.out.RepositoryDiscovery;
import com.java.system.agent.runtime.port.out.SemanticFailure;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record SemanticStepResult(
        AnalysisState state,
        SemanticStepDisposition disposition,
        Optional<SemanticFailure> failure,
        List<RepositoryDiscovery> newDiscoveries) {

    public SemanticStepResult {
        Objects.requireNonNull(state, "analysis state must not be null");
        Objects.requireNonNull(disposition, "semantic step disposition must not be null");
        Objects.requireNonNull(failure, "semantic failure must not be null");
        Objects.requireNonNull(newDiscoveries, "repository discoveries must not be null");
        newDiscoveries = List.copyOf(newDiscoveries);
    }
}
