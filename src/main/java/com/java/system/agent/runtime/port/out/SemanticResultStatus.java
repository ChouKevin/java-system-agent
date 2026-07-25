package com.java.system.agent.runtime.port.out;

public enum SemanticResultStatus {
    SUCCESS,
    PARTIAL,
    AMBIGUOUS,
    REVISION_MISMATCH,
    NOT_READY,
    TIMEOUT,
    FORBIDDEN,
    CAPABILITY_MISSING,
    FAILED
}
