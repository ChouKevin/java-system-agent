package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.capability.CapabilityDescriptor;
import com.java.system.agent.runtime.domain.candidate.IssuedCandidate;
import com.java.system.agent.runtime.domain.scope.RevisionVector;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * runtime 驗證動作後交給語意查詢 adapter 的型別化請求
 */
public record AgentSemanticQuery(
        CapabilityDescriptor capability,
        List<IssuedCandidate> candidates,
        String question,
        Map<String, String> arguments,
        RevisionVector expectedRevisions) {

    public AgentSemanticQuery {
        Objects.requireNonNull(capability, "capability must not be null");
        Objects.requireNonNull(question, "semantic query question must not be null");
        Objects.requireNonNull(expectedRevisions, "expected revisions must not be null");
        if (question.isBlank()) {
            throw new IllegalArgumentException("semantic query question must not be blank");
        }
        candidates = immutableList(candidates, "issued candidate");
        arguments = immutableArguments(arguments);
    }

    private static <T> List<T> immutableList(List<T> values, String valueDescription) {
        Objects.requireNonNull(values, valueDescription + " list must not be null");
        for (T value : values) {
            Objects.requireNonNull(value, valueDescription + " must not contain null elements");
        }
        return List.copyOf(values);
    }

    private static Map<String, String> immutableArguments(Map<String, String> values) {
        Objects.requireNonNull(values, "semantic query arguments must not be null");
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), "semantic query argument name must not be null");
            String value = Objects.requireNonNull(entry.getValue(), "semantic query argument value must not be null");
            if (name.isBlank() || value.isBlank()) {
                throw new IllegalArgumentException("semantic query argument names and values must not be blank");
            }
        }
        return Map.copyOf(values);
    }
}
