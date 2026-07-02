package com.java.system.agent.analysis.model;

import java.time.Instant;

public record AnalysisMetadata(
        String repoId,
        String packageName,
        String className,
        String methodName,
        Instant analyzedAt) {

    public static AnalysisMetadata now(String repoId, String packageName, String className, String methodName) {
        return new AnalysisMetadata(repoId, packageName, className, methodName, Instant.now());
    }
}
