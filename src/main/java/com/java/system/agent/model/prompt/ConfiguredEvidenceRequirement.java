package com.java.system.agent.model.prompt;

import java.util.List;
import java.util.Objects;

/**
 * 由 immutable prompt catalog 載入的 evidence requirement
 */
public record ConfiguredEvidenceRequirement(
        String id,
        CapabilityReference capability,
        List<String> aliases) {

    public ConfiguredEvidenceRequirement {
        id = Objects.requireNonNull(id, "configured evidence requirement id must not be null");
        capability = Objects.requireNonNull(capability, "configured evidence requirement capability must not be null");
        aliases = List.copyOf(Objects.requireNonNull(aliases, "configured evidence requirement aliases must not be null"));
        if (id.isBlank()) {
            throw new IllegalArgumentException("configured evidence requirement id must not be blank");
        }
        if (aliases.isEmpty()) {
            throw new IllegalArgumentException("configured evidence requirement must declare at least one alias");
        }
        for (String alias : aliases) {
            String requiredAlias = Objects.requireNonNull(alias,
                    "configured evidence requirement alias must not be null");
            if (requiredAlias.isBlank()) {
                throw new IllegalArgumentException("configured evidence requirement alias must not be blank");
            }
        }
    }
}
