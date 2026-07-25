package com.java.system.agent.analysis.application;

import com.java.system.agent.analysis.domain.InformationNeedType;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

public record SemanticCapability(
        String name,
        String version,
        Set<InformationNeedType> supportedNeedTypes,
        String inputSchemaVersion,
        String outputSchemaVersion,
        boolean requiresPinnedRevision,
        boolean requiresSemanticTarget,
        int estimatedCost,
        Duration timeout,
        int maxRetries) implements Comparable<SemanticCapability> {

    public SemanticCapability {
        name = requireText(name, "semantic capability name");
        version = requireText(version, "semantic capability version");
        inputSchemaVersion = requireText(inputSchemaVersion, "semantic input schema version");
        outputSchemaVersion = requireText(outputSchemaVersion, "semantic output schema version");
        Objects.requireNonNull(supportedNeedTypes, "supported information need types must not be null");
        Objects.requireNonNull(timeout, "semantic capability timeout must not be null");
        supportedNeedTypes = Set.copyOf(supportedNeedTypes);
        if (supportedNeedTypes.size() < 1) {
            throw new IllegalArgumentException("semantic capability must support at least one need type");
        }
        if (estimatedCost < 1 || timeout.isNegative() || timeout.isZero() || maxRetries < 0) {
            throw new IllegalArgumentException("semantic capability cost, timeout, or retry policy is invalid");
        }
    }

    public String qualifiedName() {
        return name + "/" + version;
    }

    public boolean supports(InformationNeedType needType) {
        Objects.requireNonNull(needType, "information need type must not be null");
        return supportedNeedTypes.contains(needType);
    }

    @Override
    public int compareTo(SemanticCapability other) {
        Objects.requireNonNull(other, "semantic capability must not be null");
        return qualifiedName().compareTo(other.qualifiedName());
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label + " must not be null");
        String normalized = value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return normalized;
    }
}
