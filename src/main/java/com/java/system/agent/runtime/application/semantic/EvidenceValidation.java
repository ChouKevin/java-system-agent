package com.java.system.agent.runtime.application.semantic;

import java.util.Objects;
import java.util.Optional;

/**
 * {@link SemanticEvidenceValidator#validate} 的判定結果
 *
 * <p>{@code VALID} 帶著 {@link ValidatedEvidence}；{@code STALE} 代表證據所附的 revision
 * 已經過期；{@code PROTOCOL_ERROR} 代表語意服務回傳的結果違反了 revision-bound 證據契約</p>
 */
record EvidenceValidation(
        EvidenceValidationOutcome outcome,
        Optional<ValidatedEvidence> validatedEvidence) {

    EvidenceValidation {
        Objects.requireNonNull(outcome, "evidence validation outcome must not be null");
        Objects.requireNonNull(validatedEvidence, "validated evidence must not be null");
        if ((outcome == EvidenceValidationOutcome.VALID) != validatedEvidence.isPresent()) {
            throw new IllegalArgumentException(
                    "valid evidence outcome requires exactly one validated evidence value");
        }
    }

    static EvidenceValidation valid(ValidatedEvidence validatedEvidence) {
        return new EvidenceValidation(
                EvidenceValidationOutcome.VALID,
                Optional.of(Objects.requireNonNull(
                        validatedEvidence, "validated evidence must not be null")));
    }

    static EvidenceValidation stale() {
        return new EvidenceValidation(EvidenceValidationOutcome.STALE, Optional.empty());
    }

    static EvidenceValidation protocolError() {
        return new EvidenceValidation(EvidenceValidationOutcome.PROTOCOL_ERROR, Optional.empty());
    }
}
