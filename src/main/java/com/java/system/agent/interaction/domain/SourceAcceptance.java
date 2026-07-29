package com.java.system.agent.interaction.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * 來源事件 durable admission 或 immutable contract conflict 的結果
 */
public record SourceAcceptance(
        SourceAcceptanceStatus status,
        Optional<SourceAdmission> admission,
        Optional<SourceEventConflictScope> conflictScope) {

    public SourceAcceptance {
        Objects.requireNonNull(status, "source acceptance status must not be null");
        Objects.requireNonNull(admission, "source admission must not be null");
        Objects.requireNonNull(conflictScope, "source conflict scope must not be null");
        switch (status) {
            case ACCEPTED, DUPLICATE -> {
                if (!admission.isPresent() || conflictScope.isPresent()) {
                    throw new IllegalArgumentException("accepted source event must contain only an admission");
                }
            }
            case CONTRACT_FAILED -> {
                if (admission.isPresent() || !conflictScope.isPresent()) {
                    throw new IllegalArgumentException("failed source contract must contain only a conflict scope");
                }
            }
        }
    }
}
