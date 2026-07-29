package com.java.system.agent.interaction.application;

import com.java.system.agent.interaction.domain.delivery.DeliveryClaim;
import com.java.system.agent.interaction.domain.delivery.DeliveryProcessingOutcome;
import com.java.system.agent.interaction.port.in.ProcessNextDeliveryUseCase;
import com.java.system.agent.interaction.port.in.ClaimRecoveryFailureException;
import com.java.system.agent.interaction.port.out.DeliveryOutboxPort;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 將一次 delivery polling claim 交給既有 processor 的 application service
 */
public final class DeliveryWorkApplicationService implements ProcessNextDeliveryUseCase {

    private final DeliveryOutboxPort deliveryOutboxPort;
    private final DeliveryProcessor deliveryProcessor;
    private final ClaimAdmissionCoordinator claimAdmission;

    public DeliveryWorkApplicationService(
            DeliveryOutboxPort deliveryOutboxPort,
            DeliveryProcessor deliveryProcessor,
            ClaimAdmissionCoordinator claimAdmission) {
        this.deliveryOutboxPort = Objects.requireNonNull(deliveryOutboxPort, "delivery outbox port must not be null");
        this.deliveryProcessor = Objects.requireNonNull(deliveryProcessor, "delivery processor must not be null");
        this.claimAdmission = Objects.requireNonNull(claimAdmission, "claim admission coordinator must not be null");
    }

    @Override
    public Optional<DeliveryProcessingOutcome> processNext(Instant now) {
        Objects.requireNonNull(now, "delivery processing time must not be null");
        Optional<DeliveryClaim> claim = claimAdmission.claimIfOpen(() -> deliveryOutboxPort.claimNext(now));
        return claim.map(nextClaim -> processClaim(nextClaim, now));
    }

    private DeliveryProcessingOutcome processClaim(DeliveryClaim claim, Instant now) {
        try {
            return deliveryProcessor.process(claim, now);
        } catch (RuntimeException processingFailure) {
            recoverOrStop(claim, now, processingFailure);
            throw processingFailure;
        }
    }

    private void recoverOrStop(DeliveryClaim claim, Instant now, RuntimeException processingFailure) {
        try {
            if (!deliveryOutboxPort.recoverClaim(claim, now)) {
                throw new ClaimRecoveryFailureException(processingFailure);
            }
        } catch (ClaimRecoveryFailureException exception) {
            throw exception;
        } catch (RuntimeException recoveryFailure) {
            throw new ClaimRecoveryFailureException(processingFailure, recoveryFailure);
        }
    }
}
