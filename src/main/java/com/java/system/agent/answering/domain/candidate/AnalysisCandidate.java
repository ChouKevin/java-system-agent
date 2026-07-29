package com.java.system.agent.answering.domain.candidate;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;

import java.util.Optional;

/**
 * 語意服務回傳但尚未由 answering 配發 handle 的候選項目
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "candidate_type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = RepositoryCandidate.class, name = "REPOSITORY"),
        @JsonSubTypes.Type(value = RouteCandidate.class, name = "ROUTE"),
        @JsonSubTypes.Type(value = SemanticTargetCandidate.class, name = "SEMANTIC_TARGET")
})
public sealed interface AnalysisCandidate permits RepositoryCandidate, RouteCandidate, SemanticTargetCandidate {

    CandidateKind kind();

    RepositoryId repositoryId();

    Optional<RepositoryRevision> repositoryRevision();

    String description();
}
