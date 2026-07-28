package com.java.system.agent.inbox.application;

import com.java.system.agent.inbox.port.in.StopClaimingUseCase;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 在線性化關閉期間決定 inbox 與 delivery persistence claim 是否可開始
 */
public final class ClaimAdmissionCoordinator implements StopClaimingUseCase {

    private final AtomicBoolean acceptingClaims = new AtomicBoolean(true);

    /**
     * 原子 admission 決定在關閉前完成的 claim 可繼續執行，不將 supplier 納入同步範圍
     */
    public <T> Optional<T> claimIfOpen(Supplier<Optional<T>> claim) {
        Supplier<Optional<T>> verifiedClaim = Objects.requireNonNull(claim, "claim supplier must not be null");
        if (!acceptingClaims.get()) {
            return Optional.empty();
        }
        return verifiedClaim.get();
    }

    @Override
    public void stopClaiming() {
        acceptingClaims.set(false);
    }
}
