package com.java.system.agent.analysis.model;

import java.util.List;

public record CallEdge(
        MethodId caller,
        MethodId callee,
        String callExpression,
        Integer lineNumber,
        ResolutionStrategy resolutionStrategy,
        double confidence,
        List<String> warnings) {
}
