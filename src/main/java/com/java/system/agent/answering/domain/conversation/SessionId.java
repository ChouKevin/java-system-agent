package com.java.system.agent.answering.domain.conversation;

import java.util.Objects;

/**
 * 一個 Slack thread 的識別碼
 *
 * <p>用來把 append-only session history 綁定到特定 thread 上</p>
 */
public record SessionId(String value) {

    public SessionId {
        Objects.requireNonNull(value, "session ID must not be null");
        value = value.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("session ID must not be blank");
        }
    }
}
