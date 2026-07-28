package com.java.system.agent.slack.delivery;

import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 單一 process 內避免同一 Slack channel 過快送訊的 gate
 */
public final class SlackChannelRateGate {

    private final Map<String, Instant> nextSafeByChannel = new HashMap<>();

    /**
     * 保留下一個 channel dispatch slot 或回傳安全重試時間
     */
    public synchronized Optional<Instant> reserve(String channel, Instant now) {
        if (!StringUtils.hasText(channel)) {
            throw new IllegalArgumentException("Slack channel must not be blank");
        }
        Instant nextSafe = nextSafeByChannel.get(channel);
        if (Objects.nonNull(nextSafe) && now.isBefore(nextSafe)) {
            return Optional.of(nextSafe);
        }
        nextSafeByChannel.put(channel, now.plusSeconds(1));
        return Optional.empty();
    }

    /**
     * 讓 Slack 回傳的較晚重試時間成為權威
     */
    public synchronized void extend(String channel, Instant retryAt) {
        Instant current = nextSafeByChannel.get(channel);
        if (Objects.isNull(current) || retryAt.isAfter(current)) {
            nextSafeByChannel.put(channel, retryAt);
        }
    }
}
