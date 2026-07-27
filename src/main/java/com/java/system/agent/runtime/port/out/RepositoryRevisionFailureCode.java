package com.java.system.agent.runtime.port.out;

/**
 * Repository revision 邊界的 provider-neutral 失敗分類
 */
public enum RepositoryRevisionFailureCode {
    DEPENDENCY_NOT_READY, TIMEOUT, FORBIDDEN, REPOSITORY_NOT_FOUND, DEPENDENCY_UNAVAILABLE, DEPENDENCY_FAILURE
}
