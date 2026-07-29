package com.java.system.agent.capability.planning;

import com.java.system.agent.answering.domain.handle.CandidateHandleRef;

import java.util.List;
import java.util.Objects;

/**
 * planning mapper 從模型輸入分離 raw 候選參考與 capability 專屬 execution input 的結果
 */
public record QueryPlanningSelection<E>(
        List<CandidateHandleRef> candidateReferences,
        String questionToResolve,
        String rationale,
        E executionInput) {

    public QueryPlanningSelection {
        candidateReferences = List.copyOf(Objects.requireNonNull(
                candidateReferences, "query planning candidate references must not be null"));
        Objects.requireNonNull(questionToResolve, "query planning question must not be null");
        Objects.requireNonNull(rationale, "query planning rationale must not be null");
        Objects.requireNonNull(executionInput, "query planning execution input must not be null");
        if (questionToResolve.isBlank() || rationale.isBlank()) {
            throw new IllegalArgumentException("query planning question and rationale must not be blank");
        }
    }
}
