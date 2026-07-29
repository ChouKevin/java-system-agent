package com.java.system.agent.interaction.domain;

import java.util.Objects;

/**
 * 已原子轉為 PROCESSING 的單筆 inbox 訊息
 */
public record InboxClaim(InboxMessage message) {

    public InboxClaim {
        Objects.requireNonNull(message, "inbox message must not be null");
        if (message.status() != InboxMessageStatus.PROCESSING) {
            throw new IllegalArgumentException("inbox claim must contain a processing message");
        }
    }
}
