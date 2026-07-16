package com.java.system.agent.analysis.trie;

/**
 * Trie leaf payload — contains the info needed to resolve a call graph for an API entry point.
 */
public record ApiEntryPointRef(
        String repoId,
        String packageName,
        String className,
        String methodName,
        String httpMethod,
        String routeTemplate) {

    public ApiEntryPointRef(String repoId, String packageName, String className, String methodName) {
        this(repoId, packageName, className, methodName, "", "");
    }
}
