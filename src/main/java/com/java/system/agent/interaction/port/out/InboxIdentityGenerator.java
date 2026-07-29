package com.java.system.agent.interaction.port.out;

import com.java.system.agent.interaction.domain.InboxMessageId;
import com.java.system.agent.answering.domain.conversation.SessionId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;

/**
 * Durable inbox 儲存實作配發新 opaque identity 的外部 contract
 */
public interface InboxIdentityGenerator {

    InboxMessageId nextInboxMessageId();

    SessionId nextSessionId();

    AnalysisRunId nextRunId();

    String nextDeliveryId();

    String nextConflictId();
}
