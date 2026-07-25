package com.java.system.agent.runtime.application.semantic;

/**
 * {@link SemanticEvidenceValidator} 判定證據與 discovery 之後的三種結果
 */
enum EvidenceValidationOutcome {
    VALID,
    STALE,
    PROTOCOL_ERROR
}
