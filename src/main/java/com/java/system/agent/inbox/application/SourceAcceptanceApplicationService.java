package com.java.system.agent.inbox.application;

import com.java.system.agent.inbox.domain.NormalizedSourceEvent;
import com.java.system.agent.inbox.domain.SourceAcceptance;
import com.java.system.agent.inbox.port.in.AcceptSourceEventUseCase;
import com.java.system.agent.inbox.port.out.SourceAcceptancePort;

import java.util.Objects;

/**
 * 將正規化來源事件交由 durable admission port 處理的 application boundary
 */
public final class SourceAcceptanceApplicationService implements AcceptSourceEventUseCase {

    private final SourceAcceptancePort sourceAcceptancePort;
    private final InboxLifecycleMetrics metrics;

    public SourceAcceptanceApplicationService(SourceAcceptancePort sourceAcceptancePort) {
        this(sourceAcceptancePort, InboxLifecycleMetrics.NO_OP);
    }

    public SourceAcceptanceApplicationService(SourceAcceptancePort sourceAcceptancePort, InboxLifecycleMetrics metrics) {
        this.sourceAcceptancePort = Objects.requireNonNull(sourceAcceptancePort, "source acceptance port must not be null");
        this.metrics = Objects.requireNonNull(metrics, "agent lifecycle metrics must not be null");
    }

    @Override
    public SourceAcceptance accept(NormalizedSourceEvent event) {
        SourceAcceptance acceptance = sourceAcceptancePort.accept(
                Objects.requireNonNull(event, "normalized source event must not be null"));
        if (acceptance.status() == com.java.system.agent.inbox.domain.SourceAcceptanceStatus.ACCEPTED) {
            metrics.sourceAccepted();
        }
        acceptance.conflictScope().ifPresent(metrics::sourceConflict);
        return acceptance;
    }
}
