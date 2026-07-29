package com.java.system.agent;

import com.java.system.agent.interaction.domain.delivery.DeliveryStatus;
import com.java.system.agent.interaction.domain.SourceEventConflictScope;
import com.java.system.agent.interaction.application.InboxLifecycleMetrics;
import com.java.system.agent.interaction.port.in.ReadAgentOperationsUseCase;
import com.java.system.agent.model.ModelLifecycleMetrics;
import com.java.system.agent.slack.SlackLifecycleMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;
import java.util.Optional;

/**
 * 在 Slack Agent composition 註冊不含來源內容或 opaque ID tag 的 bounded operations meters
 */
@Configuration(proxyBeanMethods = false)
@Profile("agent-runtime")
public final class AgentObservabilityConfiguration {

    AgentObservabilityConfiguration(MeterRegistry registry, ObjectProvider<ReadAgentOperationsUseCase> operationsProvider) {
        ReadAgentOperationsUseCase operations = operationsProvider.getIfAvailable();
        if (java.util.Objects.isNull(operations)) {
            registerBoundedCounters(registry);
            return;
        }
        Clock clock = Clock.systemUTC();
        Gauge.builder("agent.inbox.oldest.eligible.age", operations,
                        value -> seconds(value.read(clock.instant()).oldestEligibleInboxAge()))
                .description("Age in seconds of the oldest eligible inbox message")
                .register(registry);
        Gauge.builder("agent.worker.busy", operations,
                        value -> value.read(clock.instant()).workerState().ordinal() == 0 ? 1 : 0)
                .description("Whether an Agent worker loop is busy")
                .register(registry);
        for (DeliveryStatus status : DeliveryStatus.values()) {
            Gauge.builder("agent.delivery.oldest.age", operations,
                            value -> seconds(value.read(clock.instant()).oldestDeliveryAgeByStatus().get(status)))
                    .tag("state", status.name())
                    .description("Age in seconds of the oldest delivery in a durable state")
                    .register(registry);
        }
        registerBoundedCounters(registry);
    }

    @Bean
    InboxLifecycleMetrics inboxLifecycleMetrics(MeterRegistry registry) {
        return new MicrometerInboxLifecycleMetrics(registry);
    }

    @Bean
    SlackLifecycleMetrics slackLifecycleMetrics(MeterRegistry registry) {
        return new MicrometerSlackLifecycleMetrics(registry);
    }

    @Bean
    ModelLifecycleMetrics modelLifecycleMetrics(MeterRegistry registry) {
        return new MicrometerModelLifecycleMetrics(registry);
    }

    private static void registerBoundedCounters(MeterRegistry registry) {
        Counter.builder("agent.source.admission").tag("result", "ACCEPTED").register(registry);
        Counter.builder("agent.socket.acceptance.failure").tag("category", "DURABLE_ACCEPTANCE").register(registry);
        Timer.builder("agent.socket.acceptance.duration").register(registry);
        Counter.builder("agent.source.ignored").tag("category", "UNSUPPORTED_MENTION").register(registry);
        Counter.builder("agent.source.conflict").tag("scope", "TRANSPORT_EVENT_ID").register(registry);
        Counter.builder("agent.source.conflict").tag("scope", "CANONICAL_MESSAGE_ID").register(registry);
        Counter.builder("agent.inbox.capacity.deferral").register(registry);
        Counter.builder("agent.infrastructure.failure").tag("category", "INBOX").register(registry);
        Counter.builder("agent.delivery.retry").tag("category", "RETRYABLE").register(registry);
        Counter.builder("agent.delivery.blocked").tag("category", "PERMANENT").register(registry);
        Counter.builder("agent.model.request").register(registry);
        Counter.builder("agent.model.estimated.tokens").register(registry);
        Counter.builder("agent.provider.rate_limit").tag("provider", "SLACK").register(registry);
        Counter.builder("agent.provider.rate_limit").tag("provider", "MODEL").register(registry);
    }

    private static double seconds(Optional<java.time.Duration> duration) {
        return duration.map(value -> value.toNanos() / 1_000_000_000.0).orElse(0.0);
    }

    /**
     * 將固定 lifecycle event 寫入 MeterRegistry 的 composition adapter
     */
    private static final class MicrometerInboxLifecycleMetrics implements InboxLifecycleMetrics {

        private final MeterRegistry registry;

        private MicrometerInboxLifecycleMetrics(MeterRegistry registry) {
            this.registry = registry;
        }

        @Override
        public void sourceAccepted() {
            counter("agent.source.admission", "result", "ACCEPTED").increment();
        }

        @Override
        public void sourceConflict(SourceEventConflictScope scope) {
            counter("agent.source.conflict", "scope", scope.name()).increment();
        }

        @Override
        public void capacityDeferred() {
            Counter.builder("agent.inbox.capacity.deferral").register(registry).increment();
        }

        @Override
        public void infrastructureFailure() {
            counter("agent.infrastructure.failure", "category", "INBOX").increment();
        }

        @Override
        public void deliveryRetried() {
            counter("agent.delivery.retry", "category", "RETRYABLE").increment();
        }

        @Override
        public void deliveryBlocked() {
            counter("agent.delivery.blocked", "category", "PERMANENT").increment();
        }

        private Counter counter(String name, String tag, String value) {
            return Counter.builder(name).tag(tag, value).register(registry);
        }
    }

    /**
     * 將 Slack transport lifecycle event 寫入 MeterRegistry 的 composition adapter
     */
    private static final class MicrometerSlackLifecycleMetrics implements SlackLifecycleMetrics {

        private final MeterRegistry registry;

        private MicrometerSlackLifecycleMetrics(MeterRegistry registry) {
            this.registry = registry;
        }

        @Override
        public void unsupportedMentionIgnored() {
            counter("agent.source.ignored", "category", "UNSUPPORTED_MENTION").increment();
        }

        @Override
        public void socketAcceptance(java.time.Duration duration) {
            Timer.builder("agent.socket.acceptance.duration").register(registry).record(duration);
        }

        @Override
        public void socketAcceptanceFailure() {
            counter("agent.socket.acceptance.failure", "category", "DURABLE_ACCEPTANCE").increment();
        }

        @Override
        public void providerRateLimited() {
            counter("agent.provider.rate_limit", "provider", "SLACK").increment();
        }

        private Counter counter(String name, String tag, String value) {
            return Counter.builder(name).tag(tag, value).register(registry);
        }
    }

    /**
     * 將 model provider lifecycle event 寫入 MeterRegistry 的 composition adapter
     */
    private static final class MicrometerModelLifecycleMetrics implements ModelLifecycleMetrics {

        private final MeterRegistry registry;

        private MicrometerModelLifecycleMetrics(MeterRegistry registry) {
            this.registry = registry;
        }

        @Override
        public void requested(long estimatedTokens) {
            Counter.builder("agent.model.request").register(registry).increment();
            Counter.builder("agent.model.estimated.tokens").register(registry).increment(estimatedTokens);
        }

        @Override
        public void providerRateLimited() {
            Counter.builder("agent.provider.rate_limit").tag("provider", "MODEL").register(registry).increment();
        }
    }
}
