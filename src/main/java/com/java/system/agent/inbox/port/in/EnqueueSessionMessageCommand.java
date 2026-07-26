package com.java.system.agent.inbox.port.in;

import com.java.system.agent.inbox.domain.SessionSourceRef;
import com.java.system.agent.inbox.domain.SourceMessageId;

import java.util.Objects;

/**
 * 上游將一則 session 訊息交給 durable inbox 的輸入
 */
public record EnqueueSessionMessageCommand(
        SessionSourceRef source,
        SourceMessageId sourceMessageId,
        String exactQuestion) {

    public EnqueueSessionMessageCommand {
        Objects.requireNonNull(source, "session source reference must not be null");
        Objects.requireNonNull(sourceMessageId, "source message ID must not be null");
        Objects.requireNonNull(exactQuestion, "exact question must not be null");
        if (exactQuestion.isBlank()) {
            throw new IllegalArgumentException("exact question must not be blank");
        }
    }
}
