package com.java.system.agent.answering.domain.candidate;

import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;

import java.util.Objects;
import java.util.Optional;

/**
 * 只識別 repository 而未宣告分析 revision 的候選項目
 */
public record RepositoryCandidate(RepositoryId repositoryId, String description) implements AnalysisCandidate {

    public RepositoryCandidate {
        Objects.requireNonNull(repositoryId, "repository candidate repository ID must not be null");
        Objects.requireNonNull(description, "repository candidate description must not be null");
        if (description.isBlank()) {
            throw new IllegalArgumentException("repository candidate description must not be blank");
        }
    }

    @Override
    public CandidateKind kind() {
        return CandidateKind.REPOSITORY;
    }

    @Override
    public Optional<RepositoryRevision> repositoryRevision() {
        return Optional.empty();
    }
}
