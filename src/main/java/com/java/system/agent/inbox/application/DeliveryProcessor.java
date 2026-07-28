package com.java.system.agent.inbox.application;

import com.java.system.agent.inbox.domain.delivery.DeliveryClaim;
import com.java.system.agent.inbox.domain.delivery.DeliveryFailure;
import com.java.system.agent.inbox.domain.delivery.DeliveryMessage;
import com.java.system.agent.inbox.domain.delivery.DeliveryProcessingOutcome;
import com.java.system.agent.inbox.domain.delivery.DeliveryTransportResult;
import com.java.system.agent.inbox.port.out.DeliveryOutboxPort;
import com.java.system.agent.inbox.port.out.DeliveryTransportPort;

import java.time.Instant;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 處理一筆已認領 delivery 並持久化 transport 結果的 application service
 */
public final class DeliveryProcessor {

    private static final int MAX_DIAGNOSTIC_CAUSE_DEPTH = 16;
    private static final DeliveryFailure UNEXPECTED_FAILURE = new DeliveryFailure(
            "DELIVERY_UNEXPECTED", "Unexpected delivery failure");
    private static final DeliveryFailure ATTEMPTS_EXHAUSTED_FAILURE = new DeliveryFailure(
            "DELIVERY_ATTEMPTS_EXHAUSTED", "Delivery attempts exhausted; manual intervention required");
    private static final String RETRY_DESCRIPTION = "Delivery transport requested retry";
    private static final Logger LOGGER = Logger.getLogger(DeliveryProcessor.class.getName());

    private final DeliveryOutboxPort deliveryOutboxPort;
    private final DeliveryTransportPort deliveryTransportPort;
    private final DeliveryRetryPolicy retryPolicy;
    private final InboxLifecycleMetrics metrics;

    public DeliveryProcessor(
            DeliveryOutboxPort deliveryOutboxPort,
            DeliveryTransportPort deliveryTransportPort,
            DeliveryRetryPolicy retryPolicy) {
        this(deliveryOutboxPort, deliveryTransportPort, retryPolicy, InboxLifecycleMetrics.NO_OP);
    }

    public DeliveryProcessor(
            DeliveryOutboxPort deliveryOutboxPort,
            DeliveryTransportPort deliveryTransportPort,
            DeliveryRetryPolicy retryPolicy,
            InboxLifecycleMetrics metrics) {
        this.deliveryOutboxPort = Objects.requireNonNull(deliveryOutboxPort, "delivery outbox port must not be null");
        this.deliveryTransportPort = Objects.requireNonNull(deliveryTransportPort, "delivery transport port must not be null");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "delivery retry policy must not be null");
        this.metrics = Objects.requireNonNull(metrics, "agent lifecycle metrics must not be null");
    }

    public DeliveryProcessingOutcome process(DeliveryClaim claim, Instant now) {
        Objects.requireNonNull(claim, "delivery claim must not be null");
        Objects.requireNonNull(now, "delivery processing time must not be null");
        if (retryPolicy.exceedsMaximumAttempts(claim.message().attemptCount())) {
            return block(claim, ATTEMPTS_EXHAUSTED_FAILURE, now);
        }
        DeliveryTransportResult result;
        try {
            result = Objects.requireNonNull(
                    deliveryTransportPort.deliver(claim.message(), now), "delivery transport result must not be null");
        } catch (Exception exception) {
            logUnexpectedFailure(claim.message(), exception);
            return retryOrBlock(claim, UNEXPECTED_FAILURE, now);
        }
        return persistResult(claim, result, now);
    }

    private DeliveryProcessingOutcome persistResult(DeliveryClaim claim, DeliveryTransportResult result, Instant now) {
        if (result instanceof DeliveryTransportResult.Delivered delivered) {
            deliveryOutboxPort.recordDelivered(claim, delivered.providerMessageId(), now);
            return DeliveryProcessingOutcome.DELIVERED;
        }
        if (result instanceof DeliveryTransportResult.RetryableFailure retryableFailure) {
            DeliveryFailure failure = new DeliveryFailure(retryableFailure.category(), RETRY_DESCRIPTION);
            return retryOrBlock(claim, failure, retryableFailure.retryAt(), now);
        }
        DeliveryTransportResult.PermanentFailure permanentFailure = (DeliveryTransportResult.PermanentFailure) result;
        metrics.deliveryBlocked();
        DeliveryFailure failure = new DeliveryFailure(permanentFailure.category(), permanentFailure.safeDescription());
        deliveryOutboxPort.recordBlocked(claim, failure, now);
        return DeliveryProcessingOutcome.BLOCKED;
    }

    private DeliveryProcessingOutcome retryOrBlock(DeliveryClaim claim, DeliveryFailure failure, Instant now) {
        if (!retryPolicy.canRetry(claim.message().attemptCount())) {
            return block(claim, failure, now);
        }
        Instant retryAt = retryPolicy.retryAt(claim.message().attemptCount(), now);
        return scheduleRetry(claim, failure, retryAt, now);
    }

    private DeliveryProcessingOutcome retryOrBlock(
            DeliveryClaim claim,
            DeliveryFailure failure,
            Instant transportRetryAt,
            Instant now) {
        if (!retryPolicy.canRetry(claim.message().attemptCount())) {
            return block(claim, failure, now);
        }
        Instant retryAt = transportRetryAt.isAfter(now)
                ? transportRetryAt
                : retryPolicy.retryAt(claim.message().attemptCount(), now);
        return scheduleRetry(claim, failure, retryAt, now);
    }

    private DeliveryProcessingOutcome scheduleRetry(
            DeliveryClaim claim,
            DeliveryFailure failure,
            Instant retryAt,
            Instant now) {
        metrics.deliveryRetried();
        deliveryOutboxPort.recordRetry(claim, failure, retryAt, now);
        return DeliveryProcessingOutcome.RETRY_SCHEDULED;
    }

    private DeliveryProcessingOutcome block(DeliveryClaim claim, DeliveryFailure failure, Instant now) {
        metrics.deliveryBlocked();
        deliveryOutboxPort.recordBlocked(claim, failure, now);
        return DeliveryProcessingOutcome.BLOCKED;
    }

    private static void logUnexpectedFailure(DeliveryMessage message, Exception exception) {
        LogRecord record = new LogRecord(
                Level.WARNING,
                "delivery category={0} deliveryId={1} runId={2} inboxId={3} attempt={4}");
        record.setParameters(new Object[]{
                UNEXPECTED_FAILURE.category(),
                message.deliveryId().value(),
                message.runId().value(),
                message.inboxMessageId().value(),
                message.attemptCount()});
        record.setThrown(sanitizedDiagnostic(exception));
        LOGGER.log(record);
    }

    private static Throwable sanitizedDiagnostic(Throwable exception) {
        return sanitizedDiagnostic(exception, new IdentityHashMap<>(), 0);
    }

    private static Throwable sanitizedDiagnostic(
            Throwable exception,
            IdentityHashMap<Throwable, Boolean> visited,
            int depth) {
        if (depth >= MAX_DIAGNOSTIC_CAUSE_DEPTH || visited.containsKey(exception)) {
            return new SanitizedDiagnosticException("diagnostic cause chain truncated");
        }
        visited.put(exception, Boolean.TRUE);
        SanitizedDiagnosticException diagnostic = new SanitizedDiagnosticException(exception.getClass().getName());
        diagnostic.setStackTrace(exception.getStackTrace());
        Optional.ofNullable(exception.getCause())
                .map(cause -> sanitizedDiagnostic(cause, visited, Math.incrementExact(depth)))
                .ifPresent(diagnostic::initCause);
        return diagnostic;
    }

    /**
     * 承載已移除原始訊息的 delivery failure 診斷鏈節點
     */
    private static final class SanitizedDiagnosticException extends RuntimeException {

        private SanitizedDiagnosticException(String exceptionType) {
            super("exception type=" + exceptionType);
        }
    }
}
