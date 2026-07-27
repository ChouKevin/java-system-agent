package com.java.system.agent.runtime.domain.run;

/**
 * 已持久化回答驗證不再可安全執行的原因
 */
public enum AnswerVerificationAbandonReason {
    RETRY_EXHAUSTED,
    CANCELLED,
    INTEGRATION_CONTRACT_FAILURE
}
