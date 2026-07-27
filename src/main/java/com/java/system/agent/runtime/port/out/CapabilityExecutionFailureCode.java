package com.java.system.agent.runtime.port.out;

/**
 * Capability 執行的 provider-neutral 失敗分類
 */
public enum CapabilityExecutionFailureCode {
    REVISION_CONFLICT, DEPENDENCY_NOT_READY, TIMEOUT, FORBIDDEN, CAPABILITY_UNAVAILABLE,
    REPOSITORY_NOT_FOUND, DEPENDENCY_UNAVAILABLE, DEPENDENCY_FAILURE
}
