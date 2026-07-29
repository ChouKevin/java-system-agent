package com.java.system.agent.runtime.application.validation;

import com.java.system.agent.runtime.domain.action.AgentAction;
import com.java.system.agent.runtime.domain.candidate.IssuedCandidate;

import java.util.List;
import java.util.Objects;

/**
 * 純動作驗證的接受或原始動作拒絕結果
 */
public sealed interface ActionValidation permits ActionValidation.Accepted, ActionValidation.Rejected {
    record Accepted(AgentAction originalAction, List<IssuedCandidate> resolvedCandidates) implements ActionValidation {
        public Accepted {
            Objects.requireNonNull(originalAction, "accepted original action must not be null");
            resolvedCandidates = List.copyOf(Objects.requireNonNull(
                    resolvedCandidates, "accepted resolved candidates must not be null"));
        }
    }

    record Rejected(ActionRejectionCode code, String description, AgentAction originalAction) implements ActionValidation {
        public Rejected {
            Objects.requireNonNull(code, "action rejection code must not be null");
            Objects.requireNonNull(description, "action rejection description must not be null");
            Objects.requireNonNull(originalAction, "rejected original action must not be null");
            if (description.isBlank()) throw new IllegalArgumentException("action rejection description must not be blank");
        }
    }
}
