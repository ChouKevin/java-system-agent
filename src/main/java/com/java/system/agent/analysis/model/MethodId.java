package com.java.system.agent.analysis.model;

import java.util.List;

public record MethodId(
        String repoId,
        String packageName,
        String className,
        String methodName,
        List<String> parameterTypes) {
}
