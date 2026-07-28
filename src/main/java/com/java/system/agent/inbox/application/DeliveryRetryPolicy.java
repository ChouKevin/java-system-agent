package com.java.system.agent.inbox.application;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * Delivery 非預期失敗後計算具上限 exponential backoff 的純規則
 */
public record DeliveryRetryPolicy(
        Duration baseDelay,
        Duration maximumDelay,
        IntFunction<Duration> jitter,
        int maximumAttempts) {

    public static final int DEFAULT_MAXIMUM_ATTEMPTS = 5;

    public DeliveryRetryPolicy(Duration baseDelay, Duration maximumDelay, IntFunction<Duration> jitter) {
        this(baseDelay, maximumDelay, jitter, DEFAULT_MAXIMUM_ATTEMPTS);
    }

    public DeliveryRetryPolicy {
        Objects.requireNonNull(baseDelay, "delivery base retry delay must not be null");
        Objects.requireNonNull(maximumDelay, "delivery maximum retry delay must not be null");
        Objects.requireNonNull(jitter, "delivery retry jitter must not be null");
        if (baseDelay.isNegative() || baseDelay.isZero()) {
            throw new IllegalArgumentException("delivery base retry delay must be positive");
        }
        if (maximumDelay.isNegative() || maximumDelay.isZero() || maximumDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("delivery maximum retry delay must be at least the base delay");
        }
        if (maximumAttempts < 1) {
            throw new IllegalArgumentException("delivery maximum attempts must be positive");
        }
    }

    /**
     * 判斷已認領的 delivery 是否仍可自動重試
     */
    public boolean canRetry(int attemptCount) {
        if (attemptCount < 1) {
            throw new IllegalArgumentException("delivery attempt count must be positive");
        }
        return attemptCount < maximumAttempts;
    }

    /**
     * 判斷已認領的 delivery 是否超過可執行的最大嘗試次數
     */
    public boolean exceedsMaximumAttempts(int attemptCount) {
        if (attemptCount < 1) {
            throw new IllegalArgumentException("delivery attempt count must be positive");
        }
        return attemptCount > maximumAttempts;
    }

    /**
     * 依已認領的 delivery attempt 計算下次非預期重試時間
     */
    public Instant retryAt(int attemptCount, Instant now) {
        Objects.requireNonNull(now, "delivery retry time must not be null");
        if (attemptCount < 1) {
            throw new IllegalArgumentException("delivery attempt count must be positive");
        }
        Duration suppliedJitter = Objects.requireNonNull(jitter.apply(attemptCount), "delivery retry jitter result must not be null");
        if (suppliedJitter.isNegative()) {
            throw new IllegalArgumentException("delivery retry jitter must not be negative");
        }
        try {
            return now.plus(cappedDelay(attemptCount)).plus(suppliedJitter);
        } catch (DateTimeException exception) {
            return Instant.MAX;
        }
    }

    private Duration cappedDelay(int attemptCount) {
        Duration delay = baseDelay;
        for (int attempt = 1; attempt < attemptCount && delay.compareTo(maximumDelay) < 0; attempt++) {
            delay = doubledOrMaximum(delay);
        }
        return delay.compareTo(maximumDelay) > 0 ? maximumDelay : delay;
    }

    private Duration doubledOrMaximum(Duration delay) {
        try {
            Duration doubled = delay.multipliedBy(2);
            return doubled.compareTo(maximumDelay) > 0 ? maximumDelay : doubled;
        } catch (ArithmeticException exception) {
            return maximumDelay;
        }
    }
}
