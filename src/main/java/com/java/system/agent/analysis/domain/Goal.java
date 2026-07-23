package com.java.system.agent.analysis.domain;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record Goal(String description, Set<InformationNeedId> requiredNeedIds) {

    public Goal {
        Objects.requireNonNull(description, "goal description must not be null");
        Objects.requireNonNull(requiredNeedIds, "required information need IDs must not be null");
        description = description.trim();
        if (description.isBlank()) {
            throw new IllegalArgumentException("goal description must not be blank");
        }
        requiredNeedIds = Collections.unmodifiableSet(new TreeSet<>(requiredNeedIds));
    }
}
