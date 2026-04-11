package com.java.system.agent.analysis.trie;

/**
 * Trie leaf payload — contains the info needed to resolve a call graph for an API entry point.
 */
public record ApiEntryPointRef(
        String repoId,
        String packageName,   // dot notation, e.g. "com.java.vip.controller"
        String className,     // e.g. "VipController"
        String methodName     // e.g. "getVip"
) {}
