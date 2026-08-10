package com.java.system.agent.model.prompt;

import java.util.Objects;

/**
 * Prompt evidence requirement 指向的 capability identity
 */
public record CapabilityReference(String name, String version) {

    public CapabilityReference {
        name = Objects.requireNonNull(name, "capability reference name must not be null");
        version = Objects.requireNonNull(version, "capability reference version must not be null");
        if (name.isBlank() || version.isBlank()) {
            throw new IllegalArgumentException("capability reference name and version must not be blank");
        }
    }
}
