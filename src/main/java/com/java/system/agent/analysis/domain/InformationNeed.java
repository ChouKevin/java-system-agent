package com.java.system.agent.analysis.domain;

import java.util.List;
import java.util.Objects;

public record InformationNeed(
        InformationNeedId id,
        InformationNeedType type,
        String question,
        boolean required,
        List<RepositoryId> repositoryCandidates,
        List<SemanticTarget> targetHints) {

    public InformationNeed {
        Objects.requireNonNull(id, "information need ID must not be null");
        Objects.requireNonNull(type, "information need type must not be null");
        Objects.requireNonNull(question, "information need question must not be null");
        Objects.requireNonNull(repositoryCandidates, "repository candidates must not be null");
        Objects.requireNonNull(targetHints, "semantic target hints must not be null");
        question = question.trim();
        if (question.isBlank()) {
            throw new IllegalArgumentException("information need question must not be blank");
        }
        repositoryCandidates = repositoryCandidates.stream()
                .map(candidate -> Objects.requireNonNull(candidate, "repository candidate must not be null"))
                .distinct()
                .sorted()
                .toList();
        targetHints = targetHints.stream()
                .map(target -> Objects.requireNonNull(target, "semantic target hint must not be null"))
                .distinct()
                .sorted()
                .toList();
    }
}
