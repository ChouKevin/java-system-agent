package com.java.system.agent.runtime.application;

import java.util.Objects;
import java.util.Optional;

public record PlanningResult(
        PlanningStatus status,
        Optional<PlannedCapability> plannedCapability,
        String diagnosis) {

    public PlanningResult {
        Objects.requireNonNull(status, "planning status must not be null");
        Objects.requireNonNull(plannedCapability, "planned capability must not be null");
        Objects.requireNonNull(diagnosis, "planning diagnosis must not be null");
        diagnosis = diagnosis.trim();
        if (diagnosis.isBlank()) {
            throw new IllegalArgumentException("planning diagnosis must not be blank");
        }
        if (status == PlanningStatus.PLANNED && !plannedCapability.isPresent()) {
            throw new IllegalArgumentException("planned result requires a capability");
        }
        if (status != PlanningStatus.PLANNED && plannedCapability.isPresent()) {
            throw new IllegalArgumentException("non-planned result cannot contain a capability");
        }
    }
}
