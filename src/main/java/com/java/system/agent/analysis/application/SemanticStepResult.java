package com.java.system.agent.analysis.application;

import com.java.system.agent.analysis.domain.AnalysisState;
import com.java.system.agent.analysis.port.out.RepositoryDiscovery;
import com.java.system.agent.analysis.port.out.SemanticFailure;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record SemanticStepResult(
        AnalysisState state,
        SemanticStepDisposition disposition,
        Optional<SemanticFailure> failure,
        List<RepositoryDiscovery> discoveries) {

    public SemanticStepResult {
        Objects.requireNonNull(state, "analysis state must not be null");
        Objects.requireNonNull(disposition, "semantic step disposition must not be null");
        Objects.requireNonNull(failure, "semantic failure must not be null");
        Objects.requireNonNull(discoveries, "repository discoveries must not be null");
        discoveries = List.copyOf(discoveries);
    }
}
