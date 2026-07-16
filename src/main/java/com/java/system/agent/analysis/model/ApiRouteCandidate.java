package com.java.system.agent.analysis.model;

public record ApiRouteCandidate(
        String repoId,
        String httpMethod,
        String routeTemplate,
        String packageName,
        String className,
        String methodName) {
}
