package com.java.system.agent.interaction.application;

import com.java.system.agent.interaction.domain.InboxClaim;
import com.java.system.agent.interaction.domain.InboxProcessingOutcome;
import com.java.system.agent.interaction.port.in.ProcessNextInboxUseCase;
import com.java.system.agent.interaction.port.in.ClaimRecoveryFailureException;
import com.java.system.agent.interaction.port.out.SessionInboxPort;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 將一次 inbox polling claim 交給既有 processor 的 application service
 */
public final class InboxWorkApplicationService implements ProcessNextInboxUseCase {

    private final SessionInboxPort sessionInboxPort;
    private final SessionInboxProcessor sessionInboxProcessor;
    private final ClaimAdmissionCoordinator claimAdmission;

    public InboxWorkApplicationService(
            SessionInboxPort sessionInboxPort,
            SessionInboxProcessor sessionInboxProcessor,
            ClaimAdmissionCoordinator claimAdmission) {
        this.sessionInboxPort = Objects.requireNonNull(sessionInboxPort, "session inbox port must not be null");
        this.sessionInboxProcessor = Objects.requireNonNull(sessionInboxProcessor, "session inbox processor must not be null");
        this.claimAdmission = Objects.requireNonNull(claimAdmission, "claim admission coordinator must not be null");
    }

    @Override
    public Optional<InboxProcessingOutcome> processNext(Instant now) {
        Objects.requireNonNull(now, "inbox processing time must not be null");
        Optional<InboxClaim> claim = claimAdmission.claimIfOpen(() -> sessionInboxPort.claimNext(now));
        return claim.map(nextClaim -> processClaim(nextClaim, now));
    }

    private InboxProcessingOutcome processClaim(InboxClaim claim, Instant now) {
        try {
            return sessionInboxProcessor.process(claim, now);
        } catch (RuntimeException processingFailure) {
            recoverOrStop(claim, now, processingFailure);
            throw processingFailure;
        }
    }

    private void recoverOrStop(InboxClaim claim, Instant now, RuntimeException processingFailure) {
        try {
            if (!sessionInboxPort.recoverClaim(claim, now)) {
                throw new ClaimRecoveryFailureException(processingFailure);
            }
        } catch (ClaimRecoveryFailureException exception) {
            throw exception;
        } catch (RuntimeException recoveryFailure) {
            throw new ClaimRecoveryFailureException(processingFailure, recoveryFailure);
        }
    }
}
