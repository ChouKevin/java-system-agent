package com.java.system.agent.ai.loop;

import com.java.system.agent.ai.config.AgentLoopProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredLlmRateLimiterTest {

    @Test
    void acquire_waitsUntilRequestMinuteWindowAllowsNextRequest() {
        AtomicLong now = new AtomicLong(0L);
        AtomicLong slept = new AtomicLong(0L);
        ConfiguredLlmRateLimiter limiter = newLimiter(
                new AgentLoopProperties.RateLimit(true, 1, 1_000, 100, 300_000L),
                now,
                slept);

        limiter.acquire(prompt("first"));
        limiter.acquire(prompt("second"));

        assertThat(slept.get()).isEqualTo(60_000L);
    }

    @Test
    void acquire_waitsWhenPreviousActualTokenUsageExhaustedMinuteWindow() {
        AtomicLong now = new AtomicLong(0L);
        AtomicLong slept = new AtomicLong(0L);
        ConfiguredLlmRateLimiter limiter = newLimiter(
                new AgentLoopProperties.RateLimit(true, 100, 10, 100, 300_000L),
                now,
                slept);

        RateLimitReservation reservation = limiter.acquire(prompt("short"));
        limiter.record(reservation, responseWithUsage(8, 2));
        limiter.acquire(prompt("next"));

        assertThat(slept.get()).isEqualTo(60_000L);
    }

    @Test
    void record_withStringResponseCountsCompletionTokensForMinuteWindow() {
        AtomicLong now = new AtomicLong(0L);
        AtomicLong slept = new AtomicLong(0L);
        ConfiguredLlmRateLimiter limiter = newLimiter(
                new AgentLoopProperties.RateLimit(true, 100, 5, 100, 300_000L),
                now,
                slept);

        RateLimitReservation reservation = limiter.acquire(prompt("abcd"));
        limiter.record(reservation, "abcdefghijklmnopqrst");
        limiter.acquire(prompt("xy"));

        assertThat(slept.get()).isEqualTo(60_000L);
    }

    @Test
    void record_withShortStringResponseCountsAsCompletionTokensWithoutSubtractingPromptReservation() {
        AtomicLong now = new AtomicLong(0L);
        AtomicLong slept = new AtomicLong(0L);
        ConfiguredLlmRateLimiter limiter = newLimiter(
                new AgentLoopProperties.RateLimit(true, 100, 4, 100, 300_000L),
                now,
                slept);

        RateLimitReservation reservation = limiter.acquire(prompt("abcdefgh"));
        limiter.record(reservation, "x");
        limiter.acquire(prompt("y"));

        assertThat(slept.get()).isEqualTo(60_000L);
    }

    @Test
    void acquire_rejectsSingleRequestEstimatedAboveTokenMinuteLimit() {
        ConfiguredLlmRateLimiter limiter = newLimiter(
                new AgentLoopProperties.RateLimit(true, 100, 1, 100, 300_000L),
                new AtomicLong(0L),
                new AtomicLong(0L));

        assertThatThrownBy(() -> limiter.acquire(prompt("abcdefgh")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tokens-per-minute");
    }

    @Test
    void acquire_doesNotWaitWhenDisabled() {
        AtomicLong now = new AtomicLong(0L);
        AtomicLong slept = new AtomicLong(0L);
        ConfiguredLlmRateLimiter limiter = newLimiter(
                new AgentLoopProperties.RateLimit(false, 1, 1, 1, 300_000L),
                now,
                slept);

        limiter.acquire(prompt("first"));
        limiter.acquire(prompt("second"));

        assertThat(slept.get()).isZero();
    }

    @Test
    void should_sleep_outside_limiter_monitor_when_rate_limited() {
        AtomicLong now = new AtomicLong(0L);
        AtomicBoolean monitorHeldDuringSleep = new AtomicBoolean(false);
        AtomicReference<ConfiguredLlmRateLimiter> limiterRef = new AtomicReference<>();
        ConfiguredLlmRateLimiter limiter = new ConfiguredLlmRateLimiter(
                new AgentLoopProperties.RateLimit(true, 1, 1_000, 100, 300_000L),
                now::get,
                millis -> {
                    if (Thread.holdsLock(limiterRef.get())) {
                        monitorHeldDuringSleep.set(true);
                    }
                    now.addAndGet(millis);
                });
        limiterRef.set(limiter);

        limiter.acquire(prompt("first"));
        limiter.acquire(prompt("second"));

        assertThat(monitorHeldDuringSleep.get()).isFalse();
    }

    @Test
    void should_not_block_record_when_another_thread_sleeps_in_acquire() throws Exception {
        AtomicLong now = new AtomicLong(0L);
        CountDownLatch sleeperEntered = new CountDownLatch(1);
        CountDownLatch releaseSleeper = new CountDownLatch(1);
        ConfiguredLlmRateLimiter limiter = new ConfiguredLlmRateLimiter(
                new AgentLoopProperties.RateLimit(true, 1, 1_000, 100, 300_000L),
                now::get,
                millis -> {
                    sleeperEntered.countDown();
                    try {
                        releaseSleeper.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    now.addAndGet(millis);
                });

        RateLimitReservation reservation = limiter.acquire(prompt("first"));
        Thread waiter = new Thread(() -> limiter.acquire(prompt("second")));
        waiter.start();
        try {
            assertThat(sleeperEntered.await(5, TimeUnit.SECONDS)).isTrue();

            CountDownLatch recordDone = new CountDownLatch(1);
            Thread recorder = new Thread(() -> {
                limiter.record(reservation, "response");
                recordDone.countDown();
            });
            recorder.start();

            assertThat(recordDone.await(1, TimeUnit.SECONDS)).isTrue();
            recorder.join(1000);
        } finally {
            releaseSleeper.countDown();
            waiter.join(5000);
        }
        assertThat(waiter.isAlive()).isFalse();
    }

    @Test
    void should_fail_before_sleep_when_required_wait_exceeds_maximum() {
        AtomicLong now = new AtomicLong(0L);
        AtomicLong slept = new AtomicLong(0L);
        ConfiguredLlmRateLimiter limiter = newLimiter(
                new AgentLoopProperties.RateLimit(true, 100, 1_000, 1, 60_000L),
                now,
                slept);

        limiter.acquire(prompt("first"));

        assertThatThrownBy(() -> limiter.acquire(prompt("second")))
                .isInstanceOf(LlmRateLimitExhaustedException.class)
                .hasMessageContaining("exceeds configured max wait");
        assertThat(slept.get()).isZero();
    }

    private ConfiguredLlmRateLimiter newLimiter(
            AgentLoopProperties.RateLimit properties,
            AtomicLong now,
            AtomicLong slept) {
        return new ConfiguredLlmRateLimiter(
                properties,
                now::get,
                millis -> {
                    slept.addAndGet(millis);
                    now.addAndGet(millis);
                });
    }

    private Prompt prompt(String text) {
        return new Prompt(List.of(new UserMessage(text)));
    }

    private ChatResponse responseWithUsage(int promptTokens, int generationTokens) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(promptTokens, generationTokens))
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))), metadata);
    }
}
