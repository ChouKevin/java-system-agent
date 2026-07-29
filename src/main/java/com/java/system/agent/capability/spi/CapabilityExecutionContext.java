package com.java.system.agent.capability.spi;

import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.scope.RevisionVector;

import java.util.List;
import java.util.Objects;

/**
 * capability module 在 answering 驗證後提供給型別化 executor 的共用執行脈絡
 */
public record CapabilityExecutionContext(
        CapabilityPolicy capability,
        List<IssuedCandidate> candidates,
        String question,
        RevisionVector expectedRevisions) {

    public CapabilityExecutionContext {
        Objects.requireNonNull(capability, "capability execution context capability must not be null");
        Objects.requireNonNull(candidates, "capability execution context candidates must not be null");
        Objects.requireNonNull(question, "capability execution context question must not be null");
        Objects.requireNonNull(expectedRevisions, "capability execution context revisions must not be null");
        if (question.isBlank()) {
            throw new IllegalArgumentException("capability execution context question must not be blank");
        }
        candidates = List.copyOf(candidates);
    }
}
