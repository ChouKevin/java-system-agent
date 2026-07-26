package com.java.system.agent.inbox.domain;

import java.util.Objects;

/**
 * 將上游 session 訊息原樣寫入 durable inbox 的請求
 */
public record InboxEnqueueRequest(
        SessionSourceRef source,
        SourceMessageId sourceMessageId,
        String exactQuestion) {

    public InboxEnqueueRequest {
        Objects.requireNonNull(source, "session source reference must not be null");
        Objects.requireNonNull(sourceMessageId, "source message ID must not be null");
        Objects.requireNonNull(exactQuestion, "exact question must not be null");
        if (exactQuestion.isBlank()) {
            throw new IllegalArgumentException("exact question must not be blank");
        }
    }
}
