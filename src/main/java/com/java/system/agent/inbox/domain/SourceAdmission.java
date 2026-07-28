package com.java.system.agent.inbox.domain;

import com.java.system.agent.runtime.domain.conversation.SessionId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;

import java.util.Objects;

/**
 * 已 durable admission 的來源事件所配發的工作識別
 */
public record SourceAdmission(
        InboxMessageId inboxMessageId,
        SessionId sessionId,
        long sessionSequence,
        AnalysisRunId runId) {

    public SourceAdmission {
        Objects.requireNonNull(inboxMessageId, "inbox message ID must not be null");
        Objects.requireNonNull(sessionId, "session ID must not be null");
        Objects.requireNonNull(runId, "analysis run ID must not be null");
        if (sessionSequence < 0) {
            throw new IllegalArgumentException("session sequence must not be negative");
        }
    }
}
