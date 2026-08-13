package com.java.system.agent.answering.domain.capability;

import java.util.Objects;

/**
 * Runtime 可配發 capability 的穩定識別。
 */
public record CapabilityPolicy(String name, String version) {

    public CapabilityPolicy {
        Objects.requireNonNull(name, "capability name must not be null");
        Objects.requireNonNull(version, "capability version must not be null");
        name = name.trim();
        version = version.trim();
        if (name.isBlank() || version.isBlank()) {
            throw new IllegalArgumentException("capability name and version must not be blank");
        }
    }
}
