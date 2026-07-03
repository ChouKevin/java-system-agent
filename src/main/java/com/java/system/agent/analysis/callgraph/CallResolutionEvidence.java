package com.java.system.agent.analysis.callgraph;

import com.java.system.agent.analysis.model.ResolutionStrategy;

import java.util.List;

public record CallResolutionEvidence(
        ResolutionStrategy resolutionStrategy,
        double confidence,
        List<String> evidence,
        List<String> warnings,
        Integer lineNumber) {
}
