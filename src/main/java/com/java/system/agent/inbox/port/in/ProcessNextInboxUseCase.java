package com.java.system.agent.inbox.port.in;

import com.java.system.agent.inbox.domain.InboxProcessingOutcome;

import java.time.Instant;
import java.util.Optional;

/**
 * 認領並處理至多一筆已到期 inbox 訊息的 inbound use-case contract
 */
public interface ProcessNextInboxUseCase {

    Optional<InboxProcessingOutcome> processNext(Instant now);
}
