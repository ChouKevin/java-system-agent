package com.java.system.agent.analysis.model;

import java.util.List;

public record CallEdge(
        MethodId caller,
        MethodId callee,
        String callExpression,
        String sourceFile,
        Integer lineNumber,
        ResolutionStrategy resolutionStrategy,
        double confidence,
        List<String> evidence,
        List<String> warnings) {

    public CallEdge(
            MethodId caller,
            MethodId callee,
            String callExpression,
            Integer lineNumber,
            ResolutionStrategy resolutionStrategy,
            double confidence,
            List<String> evidence,
            List<String> warnings) {
        this(caller, callee, callExpression, null, lineNumber, resolutionStrategy, confidence, evidence, warnings);
    }
}
