package com.java.system.agent.runtime.domain.candidate;

import com.java.system.agent.runtime.domain.evidence.SemanticTarget;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;

import java.util.Objects;
import java.util.Optional;

/**
 * 綁定已分析 revision 的語意座標候選項目
 */
public record SemanticTargetCandidate(
        RepositoryId repositoryId,
        RepositoryRevision analyzedRevision,
        SemanticTarget semanticTarget,
        String description) implements AnalysisCandidate {

    public SemanticTargetCandidate {
        Objects.requireNonNull(repositoryId, "semantic target candidate repository ID must not be null");
        Objects.requireNonNull(analyzedRevision, "semantic target candidate analyzed revision must not be null");
        Objects.requireNonNull(semanticTarget, "semantic target candidate target must not be null");
        Objects.requireNonNull(description, "semantic target candidate description must not be null");
        if (description.isBlank()) {
            throw new IllegalArgumentException("semantic target candidate description must not be blank");
        }
    }

    @Override
    public CandidateKind kind() {
        return CandidateKind.SEMANTIC_TARGET;
    }

    @Override
    public Optional<RepositoryRevision> repositoryRevision() {
        return Optional.of(analyzedRevision);
    }
}
