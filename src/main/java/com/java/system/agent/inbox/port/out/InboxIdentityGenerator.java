package com.java.system.agent.inbox.port.out;

import com.java.system.agent.inbox.domain.InboxMessageId;
import com.java.system.agent.runtime.domain.conversation.SessionId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;

/**
 * Durable inbox 儲存實作配發新 opaque identity 的外部 contract
 */
public interface InboxIdentityGenerator {

    InboxMessageId nextInboxMessageId();

    SessionId nextSessionId();

    AnalysisRunId nextRunId();
}
