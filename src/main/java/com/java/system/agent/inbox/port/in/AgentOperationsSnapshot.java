package com.java.system.agent.inbox.port.in;

import com.java.system.agent.inbox.domain.delivery.DeliveryStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 指定觀測時間的 durable queue 年齡與 in-process worker 狀態
 */
public record AgentOperationsSnapshot(
        Instant observedAt,
        Optional<Duration> oldestEligibleInboxAge,
        Map<DeliveryStatus, Optional<Duration>> oldestDeliveryAgeByStatus,
        WorkerState workerState) {

    public AgentOperationsSnapshot {
        Objects.requireNonNull(observedAt, "operations observation time must not be null");
        Objects.requireNonNull(oldestEligibleInboxAge, "oldest eligible inbox age must not be null");
        Objects.requireNonNull(oldestDeliveryAgeByStatus, "oldest delivery ages must not be null");
        Objects.requireNonNull(workerState, "worker state must not be null");
        oldestDeliveryAgeByStatus = Map.copyOf(oldestDeliveryAgeByStatus);
        if (oldestEligibleInboxAge.stream().anyMatch(Duration::isNegative)
                || oldestDeliveryAgeByStatus.values().stream()
                .flatMap(Optional::stream)
                .anyMatch(Duration::isNegative)) {
            throw new IllegalArgumentException("operations ages must not be negative");
        }
    }

    /**
     * Agent worker 在觀測瞬間是否正處理 inbox 或 delivery
     */
    public enum WorkerState {
        BUSY,
        IDLE
    }
}
