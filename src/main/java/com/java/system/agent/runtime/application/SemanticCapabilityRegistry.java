package com.java.system.agent.runtime.application;

import com.java.system.agent.runtime.domain.InformationNeedType;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

public final class SemanticCapabilityRegistry {

    private final List<SemanticCapability> capabilities;

    public SemanticCapabilityRegistry(Collection<SemanticCapability> capabilities) {
        Objects.requireNonNull(capabilities, "semantic capabilities must not be null");
        this.capabilities = capabilities.stream()
                .map(capability -> Objects.requireNonNull(capability, "semantic capability must not be null"))
                .distinct()
                .sorted()
                .toList();
    }

    public List<SemanticCapability> matching(InformationNeedType needType) {
        Objects.requireNonNull(needType, "information need type must not be null");
        return capabilities.stream()
                .filter(capability -> capability.supports(needType))
                .toList();
    }
}
