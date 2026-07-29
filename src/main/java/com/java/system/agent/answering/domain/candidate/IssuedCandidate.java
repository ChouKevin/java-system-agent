package com.java.system.agent.answering.domain.candidate;

import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;

import java.util.Objects;
import java.util.Optional;

/**
 * 已由 answering 綁定範圍並配發 handle 的候選項目
 */
public record IssuedCandidate(CandidateHandle handle, AnalysisCandidate candidate) {

    public IssuedCandidate {
        Objects.requireNonNull(handle, "issued candidate handle must not be null");
        Objects.requireNonNull(candidate, "issued candidate value must not be null");
        if (handle.kind() != candidate.kind()) {
            throw new IllegalArgumentException("candidate handle kind must match candidate kind");
        }
        Optional<RepositoryRevision> candidateRevision = candidate.repositoryRevision();
        if (candidateRevision.isPresent()
                && !handle.binding().revisionVector().matches(candidate.repositoryId(), candidateRevision.orElseThrow())) {
            throw new IllegalArgumentException("candidate revision must match handle binding revision vector");
        }
    }
}
