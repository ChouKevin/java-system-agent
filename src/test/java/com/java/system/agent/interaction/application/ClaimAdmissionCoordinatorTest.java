package com.java.system.agent.interaction.application;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ClaimAdmissionCoordinator 對關閉線性化邊界的測試
 */
class ClaimAdmissionCoordinatorTest {

    @Test
    void doesNotInvokeASupplierReleasedAfterStopWhenItWasPausedBeforeAdmission() throws Exception {
        ClaimAdmissionCoordinator coordinator = new ClaimAdmissionCoordinator();
        CountDownLatch pausedBeforeAdmission = new CountDownLatch(1);
        CountDownLatch releaseAdmission = new CountDownLatch(1);
        AtomicBoolean supplierInvoked = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            pausedBeforeAdmission.countDown();
            try {
                releaseAdmission.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            coordinator.claimIfOpen(() -> {
                supplierInvoked.set(true);
                return Optional.empty();
            });
        });

        worker.start();
        assertThat(pausedBeforeAdmission.await(1, TimeUnit.SECONDS)).isTrue();

        coordinator.stopClaiming();
        releaseAdmission.countDown();
        worker.join(1_000);

        assertThat(supplierInvoked).isFalse();
        assertThat(worker.isAlive()).isFalse();
    }
}
