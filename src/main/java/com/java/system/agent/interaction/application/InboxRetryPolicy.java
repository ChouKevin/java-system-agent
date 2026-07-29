package com.java.system.agent.interaction.application;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Inbox 執行失敗後決定是否重試與下次可執行時間的純規則
 */
public record InboxRetryPolicy(int maxAttempts, Duration baseDelay) {

    private static final int MAX_SUPPORTED_ATTEMPTS = 31;

    public InboxRetryPolicy {
        Objects.requireNonNull(baseDelay, "base delay must not be null");
        if (maxAttempts < 1 || maxAttempts > MAX_SUPPORTED_ATTEMPTS) {
            throw new IllegalArgumentException("max attempts must be between 1 and " + MAX_SUPPORTED_ATTEMPTS);
        }
        if (baseDelay.isNegative() || baseDelay.isZero()) {
            throw new IllegalArgumentException("base delay must be positive");
        }
        largestDelay(baseDelay, maxAttempts);
    }

    public static InboxRetryPolicy defaults() {
        return new InboxRetryPolicy(3, Duration.ofSeconds(1));
    }

    /**
     * 依已執行次數計算下次重試時間
     *
     * <p>若延遲後超出 {@link Instant} 可表示範圍，則沒有可排程的重試時間，呼叫端必須轉為終止失敗</p>
     */
    public Optional<Instant> retryAvailableAt(int attemptCount, Instant now) {
        Objects.requireNonNull(now, "retry time must not be null");
        if (attemptCount < 1) {
            throw new IllegalArgumentException("attempt count must be positive after execution");
        }
        if (attemptCount >= maxAttempts) {
            return Optional.empty();
        }
        try {
            return Optional.of(now.plus(delayFor(attemptCount)));
        } catch (DateTimeException exception) {
            return Optional.empty();
        }
    }

    private Duration delayFor(int attemptCount) {
        long multiplier = 1L << (attemptCount - 1);
        return baseDelay.multipliedBy(multiplier);
    }

    private static Duration largestDelay(Duration baseDelay, int maxAttempts) {
        long multiplier = 1L << Math.max(0, maxAttempts - 2);
        try {
            return baseDelay.multipliedBy(multiplier);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("retry policy delay overflows", exception);
        }
    }
}
