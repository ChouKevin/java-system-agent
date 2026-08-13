package com.java.system.agent.capability.planning;

import com.java.system.agent.answering.domain.capability.CapabilityPolicy;

import java.util.Objects;
import java.util.Optional;

/**
 * planning tool registration 對後續 prompt 投影公開的不可變中立 metadata
 */
public record PlanningToolDescriptor(
        PlanningToolCategory category,
        String toolName,
        Optional<CapabilityPolicy> capability,
        Optional<String> guidanceId) {

    public PlanningToolDescriptor {
        category = Objects.requireNonNull(category, "planning tool category must not be null");
        toolName = Objects.requireNonNull(toolName, "planning tool name must not be null");
        capability = Objects.requireNonNull(capability, "planning tool capability must not be null");
        guidanceId = Objects.requireNonNull(guidanceId, "planning tool guidance id must not be null");
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("planning tool name must not be blank");
        }
        if (guidanceId.isPresent() && guidanceId.orElseThrow().isBlank()) {
            throw new IllegalArgumentException("planning tool guidance id must not be blank");
        }
        boolean queryCategory = category == PlanningToolCategory.QUERY;
        if (queryCategory != capability.isPresent()) {
            throw new IllegalArgumentException("only QUERY planning tools must declare a capability");
        }
        if (!queryCategory && guidanceId.isPresent()) {
            throw new IllegalArgumentException("only QUERY planning tools may declare guidance");
        }
        if (capability.isPresent() && !capability.orElseThrow().name().equals(toolName)) {
            throw new IllegalArgumentException("planning tool name must match capability name");
        }
    }

    public static PlanningToolDescriptor core(PlanningToolCategory category, String toolName) {
        return new PlanningToolDescriptor(category, toolName, Optional.empty(), Optional.empty());
    }

    public static PlanningToolDescriptor query(
            PlanningToolCategory category,
            CapabilityPolicy policy,
            Optional<String> guidanceId) {
        CapabilityPolicy requiredPolicy = Objects.requireNonNull(policy, "planning tool capability policy must not be null");
        return new PlanningToolDescriptor(category, requiredPolicy.name(), Optional.of(requiredPolicy), guidanceId);
    }
}
