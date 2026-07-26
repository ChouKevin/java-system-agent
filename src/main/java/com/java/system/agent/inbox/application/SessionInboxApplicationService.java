package com.java.system.agent.inbox.application;

import com.java.system.agent.inbox.domain.InboxEnqueueRequest;
import com.java.system.agent.inbox.domain.InboxMessage;
import com.java.system.agent.inbox.port.in.EnqueueSessionMessageCommand;
import com.java.system.agent.inbox.port.in.EnqueueSessionMessageUseCase;
import com.java.system.agent.inbox.port.out.SessionInboxPort;

import java.util.Objects;

/**
 * 將公開 enqueue command 映射到 durable inbox 原子寫入的 application boundary
 */
public final class SessionInboxApplicationService implements EnqueueSessionMessageUseCase {

    private final SessionInboxPort sessionInboxPort;

    public SessionInboxApplicationService(SessionInboxPort sessionInboxPort) {
        this.sessionInboxPort = Objects.requireNonNull(sessionInboxPort, "session inbox port must not be null");
    }

    @Override
    public InboxMessage enqueue(EnqueueSessionMessageCommand command) {
        Objects.requireNonNull(command, "enqueue session message command must not be null");
        InboxEnqueueRequest request = new InboxEnqueueRequest(
                command.source(), command.sourceMessageId(), command.exactQuestion());
        return sessionInboxPort.enqueue(request);
    }
}
