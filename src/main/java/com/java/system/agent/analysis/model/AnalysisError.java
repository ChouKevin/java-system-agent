package com.java.system.agent.analysis.model;

public record AnalysisError(
        AnalysisErrorCode code,
        String message,
        String detail) {
}
