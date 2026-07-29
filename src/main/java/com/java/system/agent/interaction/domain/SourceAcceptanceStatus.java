package com.java.system.agent.interaction.domain;

/**
 * 來源事件 durable admission 的結果種類
 */
public enum SourceAcceptanceStatus {
    ACCEPTED,
    DUPLICATE,
    CONTRACT_FAILED
}
