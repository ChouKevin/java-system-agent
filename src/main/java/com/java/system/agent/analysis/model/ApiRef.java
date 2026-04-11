package com.java.system.agent.analysis.model;

public record ApiRef(
    String repoId,
    String packageName,
    String className,
    String methodName
) {}
