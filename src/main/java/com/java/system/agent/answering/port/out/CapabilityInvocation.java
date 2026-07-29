package com.java.system.agent.answering.port.out;

import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.scope.RevisionVector;

import java.util.List;
import java.util.Objects;

/**
 * answering 驗證動作後交給 capability adapter 的型別化請求
 */
public record CapabilityInvocation(
        CapabilityPolicy capability,
        List<IssuedCandidate> candidates,
        String question,
        CapabilityInputPayload payload,
        RevisionVector expectedRevisions) {

    public CapabilityInvocation {
        Objects.requireNonNull(capability, "capability must not be null");
        Objects.requireNonNull(question, "capability query question must not be null");
        Objects.requireNonNull(payload, "capability invocation payload must not be null");
        Objects.requireNonNull(expectedRevisions, "expected revisions must not be null");
        if (question.isBlank()) {
            throw new IllegalArgumentException("capability query question must not be blank");
        }
        candidates = immutableList(candidates, "issued candidate");
    }

    private static <T> List<T> immutableList(List<T> values, String valueDescription) {
        Objects.requireNonNull(values, valueDescription + " list must not be null");
        for (T value : values) {
            Objects.requireNonNull(value, valueDescription + " must not contain null elements");
        }
        return List.copyOf(values);
    }

}
