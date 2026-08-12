package com.java.system.agent.capability.planning;

import com.java.system.agent.answering.domain.handle.CandidateHandleRef;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一個目前可供模型規劃的 tool 與其依 registration 投影的候選 authority
 */
public record IssuedPlanningTool(String name, List<CandidateHandleRef> allowedCandidateHandles) {

    public IssuedPlanningTool {
        Objects.requireNonNull(name, "issued planning tool name must not be null");
        Objects.requireNonNull(allowedCandidateHandles, "issued planning tool candidate handles must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("issued planning tool name must not be blank");
        }
        Set<CandidateHandleRef> uniqueHandles = new HashSet<>(allowedCandidateHandles);
        if (uniqueHandles.size() != allowedCandidateHandles.size()) {
            throw new IllegalArgumentException("issued planning tool candidate handles must not contain duplicates");
        }
        allowedCandidateHandles = List.copyOf(allowedCandidateHandles);
    }
}
