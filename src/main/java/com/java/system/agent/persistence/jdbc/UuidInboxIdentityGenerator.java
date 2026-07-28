package com.java.system.agent.persistence.jdbc;

import com.java.system.agent.inbox.domain.InboxMessageId;
import com.java.system.agent.inbox.port.out.InboxIdentityGenerator;
import com.java.system.agent.runtime.domain.conversation.SessionId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;

import java.util.UUID;

/**
 * 以 UUID 配發 durable inbox 所需 opaque identity 的實作
 */
public final class UuidInboxIdentityGenerator implements InboxIdentityGenerator {

    @Override
    public InboxMessageId nextInboxMessageId() {
        return new InboxMessageId(UUID.randomUUID().toString());
    }

    @Override
    public SessionId nextSessionId() {
        return new SessionId(UUID.randomUUID().toString());
    }

    @Override
    public AnalysisRunId nextRunId() {
        return new AnalysisRunId(UUID.randomUUID().toString());
    }

    @Override
    public String nextDeliveryId() {
        return UUID.randomUUID().toString();
    }

    @Override
    public String nextConflictId() {
        return UUID.randomUUID().toString();
    }
}
