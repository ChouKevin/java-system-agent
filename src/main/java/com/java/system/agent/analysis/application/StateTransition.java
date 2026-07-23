package com.java.system.agent.analysis.application;

import com.java.system.agent.analysis.domain.AnalysisState;

import java.util.Objects;

public record StateTransition(AnalysisEvent event, AnalysisState candidateState) {

    public StateTransition {
        Objects.requireNonNull(event, "analysis event must not be null");
        Objects.requireNonNull(candidateState, "candidate state must not be null");
    }
}
