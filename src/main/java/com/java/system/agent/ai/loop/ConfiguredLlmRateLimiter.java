package com.java.system.agent.ai.loop;

import com.java.system.agent.ai.config.AgentLoopProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

@Component
@Slf4j
public class ConfiguredLlmRateLimiter implements LlmRateLimiter {

    private static final long MINUTE_MILLIS = TimeUnit.MINUTES.toMillis(1);
    private static final long DAY_MILLIS = TimeUnit.DAYS.toMillis(1);
    private static final int CHARS_PER_TOKEN_ESTIMATE = 4;

    private final AgentLoopProperties.RateLimit properties;
    private final LongSupplier clock;
    private final LongConsumer sleeper;
    private final Deque<UsageRecord> minuteRequests = new ArrayDeque<>();
    private final Deque<UsageRecord> dailyRequests = new ArrayDeque<>();
    private final Deque<UsageRecord> minuteTokens = new ArrayDeque<>();

    @Autowired
    public ConfiguredLlmRateLimiter(AgentLoopProperties properties) {
        this(properties.rateLimit(), System::currentTimeMillis, ConfiguredLlmRateLimiter::sleep);
    }

    ConfiguredLlmRateLimiter(
            AgentLoopProperties.RateLimit properties,
            LongSupplier clock,
            LongConsumer sleeper) {
        this.properties = properties;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    @Override
    public RateLimitReservation acquire(Prompt prompt) {
        if (!properties.enabled()) {
            return RateLimitReservation.none();
        }
        int reservedTokens = estimateTokens(prompt);
        acquire(reservedTokens);
        return new RateLimitReservation(reservedTokens);
    }

    @Override
    public RateLimitReservation acquire(String promptText) {
        if (!properties.enabled()) {
            return RateLimitReservation.none();
        }
        int reservedTokens = estimateTokens(promptText);
        acquire(reservedTokens);
        return new RateLimitReservation(reservedTokens);
    }

    @Override
    public synchronized void record(RateLimitReservation reservation, ChatResponse response) {
        if (!properties.enabled() || Objects.isNull(response) || Objects.isNull(response.getMetadata())) {
            return;
        }
        Usage usage = response.getMetadata().getUsage();
        if (Objects.isNull(usage)) {
            return;
        }
        int actualTokens = zeroIfNull(usage.getPromptTokens()) + zeroIfNull(usage.getCompletionTokens());
        recordActualTokens(reservation, actualTokens);
    }

    @Override
    public synchronized void record(RateLimitReservation reservation, String responseText) {
        if (!properties.enabled()) {
            return;
        }
        addTokenUsage(clock.getAsLong(), estimateTokens(responseText));
    }

    private void recordActualTokens(RateLimitReservation reservation, int actualTokens) {
        int additionalTokens = Math.max(0, actualTokens - reservation.reservedTokens());
        if (additionalTokens > 0) {
            addTokenUsage(clock.getAsLong(), additionalTokens);
        }
    }

    private void acquire(int reservedTokens) {
        while (true) {
            long waitMillis;
            synchronized (this) {
                long now = clock.getAsLong();
                purgeExpired(now);
                assertWithinSingleRequestTokenLimit(reservedTokens);
                waitMillis = requiredWaitMillis(now, reservedTokens);
                if (waitMillis <= 0) {
                    minuteRequests.addLast(new UsageRecord(now, 1));
                    dailyRequests.addLast(new UsageRecord(now, 1));
                    addTokenUsage(now, reservedTokens);
                    return;
                }
            }
            failFastWhenWaitExceedsMax(waitMillis);
            log.debug("LLM rate limit reached, waiting {} ms", waitMillis);
            sleeper.accept(waitMillis);
        }
    }

    /** 所需等待時間超過設定上限時直接快速失敗，非正值代表不設上限 */
    private void failFastWhenWaitExceedsMax(long waitMillis) {
        long maxWaitMillis = properties.maxWaitMillis();
        if (maxWaitMillis > 0 && waitMillis > maxWaitMillis) {
            throw new LlmRateLimitExhaustedException(waitMillis, maxWaitMillis);
        }
    }

    private long requiredWaitMillis(long now, int reservedTokens) {
        long waitMillis = 0L;
        waitMillis = Math.max(waitMillis, waitForLimit(
                minuteRequests, properties.requestsPerMinute(), 1, MINUTE_MILLIS, now));
        waitMillis = Math.max(waitMillis, waitForLimit(
                dailyRequests, properties.requestsPerDay(), 1, DAY_MILLIS, now));
        waitMillis = Math.max(waitMillis, waitForLimit(
                minuteTokens, properties.tokensPerMinute(), reservedTokens, MINUTE_MILLIS, now));
        return waitMillis;
    }

    private void assertWithinSingleRequestTokenLimit(int reservedTokens) {
        int limit = properties.tokensPerMinute();
        if (limit > 0 && reservedTokens > limit) {
            throw new IllegalStateException(
                    "Estimated LLM request tokens exceed configured tokens-per-minute limit");
        }
    }

    private long waitForLimit(Deque<UsageRecord> records, int limit, int requested, long windowMillis, long now) {
        if (limit <= 0 || requested <= 0 || sum(records) + requested <= limit) {
            return 0L;
        }
        UsageRecord first = records.peekFirst();
        if (Objects.isNull(first)) {
            return 0L;
        }
        return Math.max(1L, first.timeMillis() + windowMillis - now);
    }

    private void purgeExpired(long now) {
        purgeExpired(minuteRequests, now - MINUTE_MILLIS);
        purgeExpired(dailyRequests, now - DAY_MILLIS);
        purgeExpired(minuteTokens, now - MINUTE_MILLIS);
    }

    private void purgeExpired(Deque<UsageRecord> records, long thresholdMillis) {
        while (!CollectionUtils.isEmpty(records) && records.peekFirst().timeMillis() <= thresholdMillis) {
            records.removeFirst();
        }
    }

    private void addTokenUsage(long now, int tokens) {
        if (tokens > 0) {
            minuteTokens.addLast(new UsageRecord(now, tokens));
        }
    }

    private int sum(Deque<UsageRecord> records) {
        return records.stream()
                .mapToInt(UsageRecord::amount)
                .sum();
    }

    private int zeroIfNull(Integer value) {
        return Objects.requireNonNullElse(value, 0);
    }

    private int estimateTokens(Prompt prompt) {
        String text = prompt.getInstructions().stream()
                .map(Message::getText)
                .filter(StringUtils::hasText)
                .reduce("", (left, right) -> left + "\n" + right);
        return estimateTokens(text);
    }

    private int estimateTokens(String text) {
        if (!StringUtils.hasText(text)) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil((double) text.length() / CHARS_PER_TOKEN_ESTIMATE));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for LLM rate limit", e);
        }
    }

    private record UsageRecord(long timeMillis, int amount) {
    }
}
