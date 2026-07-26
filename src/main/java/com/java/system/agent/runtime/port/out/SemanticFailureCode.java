package com.java.system.agent.runtime.port.out;

/**
 * Agent semantic 與 repository revision 邊界可辨識的失敗代碼
 */
public enum SemanticFailureCode {
    PARTIAL_RESULT,
    AMBIGUOUS_TARGET,
    REVISION_MISMATCH,
    NOT_READY,
    TIMEOUT,
    FORBIDDEN,
    CAPABILITY_MISSING,
    REPOSITORY_NOT_FOUND,
    PROTOCOL_ERROR,
    ENGINE_UNAVAILABLE,
    ENGINE_FAILURE
}
