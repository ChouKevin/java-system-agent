package com.java.system.agent.inbox.port.out;

import com.java.system.agent.inbox.domain.delivery.DeliveryStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * persistence adapter 在指定時間讀出的 durable queue 年齡
 */
public record DurableAgentOperationsSnapshot(
        Instant observedAt,
        Optional<Duration> oldestEligibleInboxAge,
        Map<DeliveryStatus, Optional<Duration>> oldestDeliveryAgeByStatus) {

    public DurableAgentOperationsSnapshot {
        Objects.requireNonNull(observedAt, "operations observation time must not be null");
        Objects.requireNonNull(oldestEligibleInboxAge, "oldest eligible inbox age must not be null");
        Objects.requireNonNull(oldestDeliveryAgeByStatus, "oldest delivery ages must not be null");
        oldestDeliveryAgeByStatus = Map.copyOf(oldestDeliveryAgeByStatus);
        if (oldestEligibleInboxAge.stream().anyMatch(Duration::isNegative)
                || oldestDeliveryAgeByStatus.values().stream()
                .flatMap(Optional::stream)
                .anyMatch(Duration::isNegative)) {
            throw new IllegalArgumentException("operations ages must not be negative");
        }
    }
}
