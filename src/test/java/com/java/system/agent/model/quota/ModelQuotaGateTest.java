package com.java.system.agent.model.quota;

import com.java.system.agent.AgentModelRateLimitProperties;
import com.java.system.agent.model.ModelTransportFailureClassifier;
import com.java.system.agent.runtime.domain.run.ExecutionDeferral;
import com.java.system.agent.runtime.domain.run.ExecutionDeferralReason;
import com.java.system.agent.runtime.port.out.ExternalExecutionDeferredException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelQuotaGateTest {

    @Test
    void dispatchesTheFirstFifteenRequestsAndDefersTheSixteenthUntilTheOldestExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T01:02:03Z"));
        CountingChatModel provider = new CountingChatModel(responseWithoutUsage());
        ModelQuotaGate gate = gate(provider, clock, 15, 100, 500);

        for (int request = 0; request < 15; request++) {
            gate.call(new Prompt("test"));
        }

        assertThat(provider.calls()).isEqualTo(15);
        assertDeferral(gate, clock.instant().plus(Duration.ofMinutes(1)));
    }

    @Test
    void defersAggregateInputTokenOverflowUntilTheActiveReservationExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T01:02:03Z"));
        CountingChatModel provider = new CountingChatModel(responseWithoutUsage());
        ModelQuotaGate gate = gate(provider, clock, 15, 2, 500);

        gate.call(new Prompt("abcd"));
        Instant retryAt = clock.instant().plus(Duration.ofMinutes(1));

        assertDeferral(gate, retryAt, new Prompt("abcdefgh"));
        assertThat(provider.calls()).isEqualTo(1);
        clock.advanceTo(retryAt);
        gate.call(new Prompt("abcdefgh"));

        assertThat(provider.calls()).isEqualTo(2);
    }

    @Test
    void dispatchesOneOversizedPromptWhenTheMinuteWindowIsEmpty() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T01:02:03Z"));
        CountingChatModel provider = new CountingChatModel(responseWithoutUsage());
        ModelQuotaGate gate = gate(provider, clock, 15, 2, 500);

        gate.call(new Prompt("abcdefghijk"));

        assertThat(provider.calls()).isEqualTo(1);
        assertDeferral(gate, clock.instant().plus(Duration.ofMinutes(1)), new Prompt("test"));
    }

    @Test
    void defersTheFiveHundredFirstRequestUntilTheNextPacificMidnight() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-08T18:00:00Z"));
        CountingChatModel provider = new CountingChatModel(responseWithoutUsage());
        ModelQuotaGate gate = gate(provider, clock, 1_000, 10_000, 500);

        for (int request = 0; request < 500; request++) {
            gate.call(new Prompt("test"));
        }

        Instant nextPacificMidnight = ZonedDateTime.of(2026, 3, 9, 0, 0, 0, 0,
                ZoneId.of("America/Los_Angeles")).toInstant();
        assertDeferral(gate, nextPacificMidnight);
    }

    @Test
    void replacesTheEstimatedPromptTokensWithProviderUsage() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T01:02:03Z"));
        CountingChatModel provider = new CountingChatModel(responseWithPromptUsage(3));
        ModelQuotaGate gate = gate(provider, clock, 15, 4, 500);

        gate.call(new Prompt("test"));
        gate.call(new Prompt("test"));

        assertThat(provider.calls()).isEqualTo(2);
    }

    @Test
    void retainsTheEstimateWhenProviderUsageIsMissing() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T01:02:03Z"));
        CountingChatModel provider = new CountingChatModel(responseWithoutUsage());
        ModelQuotaGate gate = gate(provider, clock, 15, 1, 500);

        gate.call(new Prompt("test"));

        assertDeferral(gate, clock.instant().plus(Duration.ofMinutes(1)));
    }

    @Test
    void retainsTheEstimateWhenProviderUsageIsInvalid() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T01:02:03Z"));
        CountingChatModel provider = new CountingChatModel(responseWithPromptUsage(0));
        ModelQuotaGate gate = gate(provider, clock, 15, 1, 500);

        gate.call(new Prompt("test"));

        assertDeferral(gate, clock.instant().plus(Duration.ofMinutes(1)));
    }

    @Test
    void startsWithAnEmptyWindowAfterConstruction() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T01:02:03Z"));
        CountingChatModel originalProvider = new CountingChatModel(responseWithoutUsage());
        ModelQuotaGate originalGate = gate(originalProvider, clock, 1, 10, 500);
        originalGate.call(new Prompt("test"));
        CountingChatModel restartedProvider = new CountingChatModel(responseWithoutUsage());
        ModelQuotaGate restartedGate = gate(restartedProvider, clock, 1, 10, 500);

        restartedGate.call(new Prompt("test"));

        assertThat(restartedProvider.calls()).isEqualTo(1);
    }

    @Test
    void mapsProvider429RetryAfterDeltaSecondsToCapacityDeferral() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T01:02:03Z"));
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "30");
        CountingChatModel provider = new CountingChatModel(new HttpClientErrorException(
                HttpStatus.TOO_MANY_REQUESTS, "rate limited", headers, new byte[0], null));
        ModelQuotaGate gate = gate(provider, clock, 15, 10, 500);

        assertDeferral(gate, clock.instant().plusSeconds(30));
    }

    @Test
    void mapsWrappedSpring429AndItsRetryAfterHeaderToCapacityDeferral() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T01:02:03Z"));
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "30");
        RuntimeException providerFailure = new IllegalStateException(new HttpClientErrorException(
                HttpStatus.TOO_MANY_REQUESTS, "rate limited", headers, new byte[0], null));
        CountingChatModel provider = new CountingChatModel(providerFailure);
        ModelQuotaGate gate = gate(provider, clock, 15, 10, 500);

        assertDeferral(gate, clock.instant().plusSeconds(30));
    }

    @Test
    void usesTheConfiguredFallbackWithoutReadingBeyondTheRetryAfterCauseDepthLimit() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T01:02:03Z"));
        CauseProbeException beyondDepthLimit = new CauseProbeException();
        Throwable nested = beyondDepthLimit;
        for (int depth = 0; depth < 63; depth++) {
            nested = new IllegalStateException(nested);
        }
        ResponseStatusException rateLimited = new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, null,
                nested);
        CountingChatModel provider = new CountingChatModel(rateLimited);
        ModelQuotaGate gate = gate(provider, clock, 15, 10, 500);

        assertDeferral(gate, clock.instant().plusSeconds(45));

        assertThat(beyondDepthLimit.causeReads()).isZero();
    }

    @Test
    @Timeout(1)
    void retainsTheConfiguredFallbackForACyclicRetryAfterCauseChain() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T01:02:03Z"));
        ResponseStatusException rateLimited = new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, null,
                new CyclicCauseException());
        CountingChatModel provider = new CountingChatModel(rateLimited);
        ModelQuotaGate gate = gate(provider, clock, 15, 10, 500);

        assertDeferral(gate, clock.instant().plusSeconds(45));
    }

    @Test
    void usesARetryAfterHeaderFoundInsideTheRetryAfterCauseDepthLimit() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T01:02:03Z"));
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "30");
        HttpClientErrorException retryAfter = new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS, null,
                headers, null, null);
        ResponseStatusException rateLimited = new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, null,
                new IllegalStateException(retryAfter));
        CountingChatModel provider = new CountingChatModel(rateLimited);
        ModelQuotaGate gate = gate(provider, clock, 15, 10, 500);

        assertDeferral(gate, clock.instant().plusSeconds(30));
    }

    @Test
    void mapsWrappedProviderResourceExhaustionToTheFallbackDeferral() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T01:02:03Z"));
        CountingChatModel provider = new CountingChatModel(
                new IllegalStateException(new ResourceExhaustedException()));
        ModelQuotaGate gate = gate(provider, clock, 15, 10, 500);

        assertDeferral(gate, clock.instant().plusSeconds(45));
    }

    @Test
    @Timeout(1)
    void classifiesACyclicCauseChainWithoutHanging() {
        assertThat(ModelTransportFailureClassifier.category(new CyclicCauseException(), "rate", "unavailable"))
                .isEqualTo("unavailable");
    }

    @Test
    void mapsProvider429RetryAfterHttpDateToCapacityDeferral() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T01:02:03Z"));
        Instant retryAt = clock.instant().plusSeconds(75);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER,
                DateTimeFormatter.RFC_1123_DATE_TIME.format(retryAt.atZone(ZoneId.of("GMT"))));
        CountingChatModel provider = new CountingChatModel(new HttpClientErrorException(
                HttpStatus.TOO_MANY_REQUESTS, "rate limited", headers, new byte[0], null));
        ModelQuotaGate gate = gate(provider, clock, 15, 10, 500);

        assertDeferral(gate, retryAt);
    }

    @Test
    void mapsProvider429WithoutUsableRetryAfterToTheConfiguredFallback() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T01:02:03Z"));
        CountingChatModel provider = new CountingChatModel(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS));
        ModelQuotaGate gate = gate(provider, clock, 15, 10, 500);

        assertDeferral(gate, clock.instant().plusSeconds(45));
    }

    @Test
    void rejectsNegativeProviderRetryAfterAndUsesTheConfiguredFallback() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T01:02:03Z"));
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "-1");
        CountingChatModel provider = new CountingChatModel(new HttpClientErrorException(
                HttpStatus.TOO_MANY_REQUESTS, "rate limited", headers, new byte[0], null));
        ModelQuotaGate gate = gate(provider, clock, 15, 10, 500);

        assertDeferral(gate, clock.instant().plusSeconds(45));
    }

    @Test
    void rejectsStreamingWithoutReservingQuotaOrCallingTheProvider() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T01:02:03Z"));
        CountingChatModel provider = new CountingChatModel(responseWithoutUsage());
        ModelQuotaGate gate = gate(provider, clock, 1, 10, 500);

        assertThatThrownBy(() -> gate.stream(new Prompt("test")).blockLast())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("agent model streaming is not supported");
        gate.call(new Prompt("test"));

        assertThat(provider.calls()).isEqualTo(1);
    }

    private ModelQuotaGate gate(CountingChatModel provider, Clock clock, int requestsPerMinute,
            int inputTokensPerMinute, int requestsPerDay) {
        AgentModelRateLimitProperties properties = new AgentModelRateLimitProperties(requestsPerMinute,
                inputTokensPerMinute, requestsPerDay, Duration.ofSeconds(45));
        return new ModelQuotaGate(provider, new ModelQuotaWindow(properties.requestsPerMinute(),
                properties.inputTokensPerMinute(), properties.requestsPerDay()), new ModelInputTokenEstimator(),
                new ModelRetryAfterExtractor(), clock, properties.providerRetryFallback());
    }

    private void assertDeferral(ModelQuotaGate gate, Instant expectedRetryAt) {
        assertDeferral(gate, expectedRetryAt, new Prompt("test"));
    }

    private void assertDeferral(ModelQuotaGate gate, Instant expectedRetryAt, Prompt prompt) {
        assertThatThrownBy(() -> gate.call(prompt))
                .isInstanceOfSatisfying(ExternalExecutionDeferredException.class,
                        exception -> assertThat(exception.deferral()).isEqualTo(new ExecutionDeferral(expectedRetryAt,
                                ExecutionDeferralReason.RATE_LIMITED)));
    }

    private ChatResponse responseWithoutUsage() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("response"))));
    }

    private ChatResponse responseWithPromptUsage(int promptTokens) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(promptTokens, 1, promptTokens + 1))
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage("response"))), metadata);
    }

    private static final class CountingChatModel implements ChatModel {

        private final AtomicInteger calls = new AtomicInteger();
        private final Object outcome;

        private CountingChatModel(Object outcome) {
            this.outcome = outcome;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            if (outcome instanceof RuntimeException exception) {
                throw exception;
            }
            return (ChatResponse) outcome;
        }

        private int calls() {
            return calls.get();
        }
    }

    private static final class ResourceExhaustedException extends RuntimeException {
    }

    private static final class CyclicCauseException extends RuntimeException {

        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }

    private static final class CauseProbeException extends RuntimeException {

        private int causeReads;

        @Override
        public synchronized Throwable getCause() {
            causeReads++;
            return null;
        }

        private int causeReads() {
            return causeReads;
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advanceTo(Instant newInstant) {
            instant = newInstant;
        }
    }
}
