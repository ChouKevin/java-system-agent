package com.java.system.agent.runtime.domain.handle;

import java.util.Objects;

/**
 * Runtime 配發給 capability 的不透明代號
 */
public record CapabilityHandle(String value, HandleBinding binding) {

    public CapabilityHandle {
        Objects.requireNonNull(value, "capability handle value must not be null");
        Objects.requireNonNull(binding, "capability handle binding must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("capability handle value must not be blank");
        }
    }
}
