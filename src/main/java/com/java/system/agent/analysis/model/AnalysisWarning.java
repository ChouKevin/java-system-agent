package com.java.system.agent.analysis.model;

public record AnalysisWarning(
        String code,
        String message,
        String location) {
}
