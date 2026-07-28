package com.java.system.agent.slack.source;

import com.java.system.agent.inbox.domain.NormalizedSourceEvent;
import com.java.system.agent.inbox.port.in.AcceptSourceEventUseCase;
import com.java.system.agent.slack.SlackLifecycleMetrics;
import com.slack.api.app_backend.events.payload.EventsApiPayload;
import com.slack.api.bolt.context.builtin.EventContext;
import com.slack.api.bolt.handler.BoltEventHandler;
import com.slack.api.bolt.response.Response;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.model.event.AppMentionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.time.Duration;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * 將已篩選的 Slack app mention 直接交給 durable source admission
 */
public final class SlackAppMentionHandler implements BoltEventHandler<AppMentionEvent> {

    private static final Logger LOGGER = LoggerFactory.getLogger(SlackAppMentionHandler.class);
    private static final Duration ACK_BUDGET = Duration.ofSeconds(3);

    private final SlackMentionNormalizer normalizer;
    private final AcceptSourceEventUseCase sourceAcceptance;
    private final SlackLifecycleMetrics metrics;
    private final LongSupplier nanoTime;

    public SlackAppMentionHandler(SlackMentionNormalizer normalizer, AcceptSourceEventUseCase sourceAcceptance) {
        this(normalizer, sourceAcceptance, SlackLifecycleMetrics.NO_OP, System::nanoTime);
    }

    public SlackAppMentionHandler(
            SlackMentionNormalizer normalizer,
            AcceptSourceEventUseCase sourceAcceptance,
            SlackLifecycleMetrics metrics) {
        this(normalizer, sourceAcceptance, metrics, System::nanoTime);
    }

    SlackAppMentionHandler(
            SlackMentionNormalizer normalizer,
            AcceptSourceEventUseCase sourceAcceptance,
            SlackLifecycleMetrics metrics,
            LongSupplier nanoTime) {
        this.normalizer = Objects.requireNonNull(normalizer, "Slack mention normalizer must not be null");
        this.sourceAcceptance = Objects.requireNonNull(sourceAcceptance, "source acceptance use case must not be null");
        this.metrics = Objects.requireNonNull(metrics, "agent lifecycle metrics must not be null");
        this.nanoTime = Objects.requireNonNull(nanoTime, "monotonic clock must not be null");
    }

    /**
     * 產生 Socket Mode 失敗時不 ACK 的明確 handler
     */
    public static Function<SocketModeApp.ErrorContext, Response> noAckOnFailure() {
        return context -> {
            logAcceptanceFailure(context.getException());
            return null; // cs-allow
        };
    }

    @Override
    public Response apply(EventsApiPayload<AppMentionEvent> payload, EventContext context) {
        long startedNanos = nanoTime.getAsLong();
        try {
            Optional<NormalizedSourceEvent> normalized = normalizer.normalize(payload.getEventId(), payload.getEvent());
            if (normalized.isEmpty()) {
                return context.ack();
            }
            sourceAcceptance.accept(normalized.orElseThrow());
            if (nanoTime.getAsLong() - startedNanos >= ACK_BUDGET.toNanos()) {
                return null; // cs-allow
            }
            return context.ack();
        } catch (RuntimeException exception) {
            metrics.socketAcceptanceFailure();
            throw exception;
        } finally {
            metrics.socketAcceptance(Duration.ofNanos(nanoTime.getAsLong() - startedNanos));
        }
    }

    private static void logAcceptanceFailure(Exception exception) {
        if (Objects.isNull(exception)) {
            LOGGER.error("Slack durable acceptance failed with an unavailable exception category");
            return;
        }
        LOGGER.error("Slack durable acceptance failed with category {}", exception.getClass().getName());
    }
}
