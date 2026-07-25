package com.java.system.agent.runtime.domain.conversation;

import java.util.Objects;

/**
 * 一個 Slack thread 的識別碼
 *
 * <p>用來把 {@link ConversationContext} 綁定到特定 thread 上</p>
 */
public record ConversationId(String value) {

    public ConversationId {
        Objects.requireNonNull(value, "conversation ID must not be null");
        value = value.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("conversation ID must not be blank");
        }
    }
}
