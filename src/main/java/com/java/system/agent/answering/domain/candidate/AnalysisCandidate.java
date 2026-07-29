package com.java.system.agent.answering.domain.candidate;

import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;

import java.util.Optional;

/**
 * 語意服務回傳但尚未由 answering 配發 handle 的候選項目
 */
public sealed interface AnalysisCandidate permits RepositoryCandidate, RouteCandidate, SemanticTargetCandidate {

    CandidateKind kind();

    RepositoryId repositoryId();

    Optional<RepositoryRevision> repositoryRevision();

    String description();
}
