package com.java.system.agent.interaction.port.out;

import com.java.system.agent.interaction.domain.delivery.DeliveryClaim;
import com.java.system.agent.interaction.domain.delivery.DeliveryFailure;

import java.time.Instant;
import java.util.Optional;

/**
 * Durable delivery outbox 的原子認領與結果持久化外部邊界
 */
public interface DeliveryOutboxPort {

    Optional<DeliveryClaim> claimNext(Instant now);

    void recordDelivered(DeliveryClaim claim, String providerMessageId, Instant deliveredAt);

    void recordRetry(DeliveryClaim claim, DeliveryFailure failure, Instant retryAt, Instant updatedAt);

    void recordBlocked(DeliveryClaim claim, DeliveryFailure failure, Instant blockedAt);

    /**
     * 僅在精確 claim 仍為 PROCESSING 時將它復原為 PENDING，並保留 identity 與 attempt
     *
     * <p>回傳 false 表示該 claim 已不再是可安全復原的目前 claim</p>
     */
    default boolean recoverClaim(DeliveryClaim claim, Instant recoveredAt) {
        throw new UnsupportedOperationException("claim-specific recovery is not available");
    }

    int recoverInterrupted(Instant recoveredAt);
}
