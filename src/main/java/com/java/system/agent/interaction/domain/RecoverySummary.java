package com.java.system.agent.interaction.domain;

/**
 * 啟動時復原 interrupted inbox 與 delivery claim 的筆數
 */
public record RecoverySummary(int inboxClaims, int deliveryClaims) {

    public RecoverySummary {
        if (inboxClaims < 0) {
            throw new IllegalArgumentException("recovered inbox claim count must not be negative");
        }
        if (deliveryClaims < 0) {
            throw new IllegalArgumentException("recovered delivery claim count must not be negative");
        }
    }
}
