package com.java.system.agent;

import com.java.system.agent.inbox.application.InboxLifecycleMetrics;
import com.java.system.agent.inbox.domain.SourceEventConflictScope;
import com.java.system.agent.inbox.domain.delivery.DeliveryStatus;
import com.java.system.agent.inbox.port.in.AgentOperationsSnapshot;
import com.java.system.agent.inbox.port.in.ReadAgentOperationsUseCase;
import com.java.system.agent.model.ModelLifecycleMetrics;
import com.java.system.agent.slack.SlackLifecycleMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 驗證 Slack Agent composition 只註冊 fixed-tag meters 並由 owning boundary 各發射一次
 */
class AgentObservabilityConfigurationTest {

    @Test
    void registersDynamicOperationsGaugesAndRecordsEachLifecycleSignalExactlyOnce() {
        AtomicReference<AgentOperationsSnapshot.WorkerState> workerState = new AtomicReference<>(
                AgentOperationsSnapshot.WorkerState.BUSY);
        try (AnnotationConfigApplicationContext context = context(workerState)) {
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            InboxLifecycleMetrics inboxMetrics = context.getBean(InboxLifecycleMetrics.class);
            SlackLifecycleMetrics slackMetrics = context.getBean(SlackLifecycleMetrics.class);
            ModelLifecycleMetrics modelMetrics = context.getBean(ModelLifecycleMetrics.class);

            assertThat(registry.get("agent.inbox.oldest.eligible.age").gauge().value()).isEqualTo(5.0);
            assertThat(registry.get("agent.delivery.oldest.age").tag("state", "PENDING").gauge().value()).isEqualTo(3.0);
            assertThat(registry.get("agent.delivery.oldest.age").tag("state", "BLOCKED").gauge().value()).isZero();
            assertThat(registry.get("agent.worker.busy").gauge().value()).isEqualTo(1.0);
            workerState.set(AgentOperationsSnapshot.WorkerState.IDLE);
            assertThat(registry.get("agent.worker.busy").gauge().value()).isZero();

            inboxMetrics.sourceAccepted();
            inboxMetrics.sourceConflict(SourceEventConflictScope.TRANSPORT_EVENT_ID);
            inboxMetrics.capacityDeferred();
            inboxMetrics.infrastructureFailure();
            inboxMetrics.deliveryRetried();
            inboxMetrics.deliveryBlocked();
            slackMetrics.unsupportedMentionIgnored();
            slackMetrics.socketAcceptance(Duration.ofMillis(25));
            slackMetrics.socketAcceptanceFailure();
            slackMetrics.providerRateLimited();
            modelMetrics.requested(123L);
            modelMetrics.providerRateLimited();

            assertThat(counter(registry, "agent.source.admission", "result", "ACCEPTED")).isEqualTo(1.0);
            assertThat(counter(registry, "agent.source.conflict", "scope", "TRANSPORT_EVENT_ID")).isEqualTo(1.0);
            assertThat(counter(registry, "agent.inbox.capacity.deferral")).isEqualTo(1.0);
            assertThat(counter(registry, "agent.infrastructure.failure", "category", "INBOX")).isEqualTo(1.0);
            assertThat(counter(registry, "agent.delivery.retry", "category", "RETRYABLE")).isEqualTo(1.0);
            assertThat(counter(registry, "agent.delivery.blocked", "category", "PERMANENT")).isEqualTo(1.0);
            assertThat(counter(registry, "agent.source.ignored", "category", "UNSUPPORTED_MENTION")).isEqualTo(1.0);
            assertThat(counter(registry, "agent.socket.acceptance.failure", "category", "DURABLE_ACCEPTANCE")).isEqualTo(1.0);
            assertThat(counter(registry, "agent.provider.rate_limit", "provider", "SLACK")).isEqualTo(1.0);
            assertThat(counter(registry, "agent.provider.rate_limit", "provider", "MODEL")).isEqualTo(1.0);
            assertThat(counter(registry, "agent.model.request")).isEqualTo(1.0);
            assertThat(counter(registry, "agent.model.estimated.tokens")).isEqualTo(123.0);
            assertThat(registry.get("agent.socket.acceptance.duration").timer().count()).isEqualTo(1L);
            assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                    .allSatisfy(tag -> assertThat(tag.getKey()).isIn("result", "scope", "category", "provider", "state")));
        }
    }

    private static AnnotationConfigApplicationContext context(
            AtomicReference<AgentOperationsSnapshot.WorkerState> workerState) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("agent-runtime");
        context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
        context.registerBean(ReadAgentOperationsUseCase.class, () -> observedAt -> new AgentOperationsSnapshot(
                observedAt,
                Optional.of(Duration.ofSeconds(5)),
                deliveryAges(),
                workerState.get()));
        context.register(AgentObservabilityConfiguration.class);
        context.refresh();
        return context;
    }

    private static Map<DeliveryStatus, Optional<Duration>> deliveryAges() {
        Map<DeliveryStatus, Optional<Duration>> ages = new EnumMap<>(DeliveryStatus.class);
        for (DeliveryStatus status : DeliveryStatus.values()) {
            ages.put(status, Optional.empty());
        }
        ages.put(DeliveryStatus.PENDING, Optional.of(Duration.ofSeconds(3)));
        return ages;
    }

    private static double counter(MeterRegistry registry, String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }
}
