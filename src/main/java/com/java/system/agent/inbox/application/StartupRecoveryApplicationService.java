package com.java.system.agent.inbox.application;

import com.java.system.agent.inbox.domain.RecoverySummary;
import com.java.system.agent.inbox.port.in.RecoverInterruptedWorkUseCase;
import com.java.system.agent.inbox.port.out.DeliveryOutboxPort;
import com.java.system.agent.inbox.port.out.SessionInboxPort;

import java.time.Instant;
import java.util.Objects;

/**
 * 聚合啟動時 inbox 與 delivery interrupted claim 復原的 application service
 */
public final class StartupRecoveryApplicationService implements RecoverInterruptedWorkUseCase {

    private final SessionInboxPort sessionInboxPort;
    private final DeliveryOutboxPort deliveryOutboxPort;

    public StartupRecoveryApplicationService(SessionInboxPort sessionInboxPort, DeliveryOutboxPort deliveryOutboxPort) {
        this.sessionInboxPort = Objects.requireNonNull(sessionInboxPort, "session inbox port must not be null");
        this.deliveryOutboxPort = Objects.requireNonNull(deliveryOutboxPort, "delivery outbox port must not be null");
    }

    @Override
    public RecoverySummary recoverInterrupted(Instant recoveredAt) {
        Objects.requireNonNull(recoveredAt, "recovery time must not be null");
        int inboxClaims = sessionInboxPort.recoverInterrupted(recoveredAt);
        int deliveryClaims = deliveryOutboxPort.recoverInterrupted(recoveredAt);
        return new RecoverySummary(inboxClaims, deliveryClaims);
    }
}
