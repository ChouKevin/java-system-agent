package com.java.system.agent.runtime.domain.candidate;

import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;

import java.util.Objects;
import java.util.Optional;

/**
 * 綁定已分析 revision 的 HTTP route 候選項目
 */
public record RouteCandidate(
        RepositoryId repositoryId,
        RepositoryRevision analyzedRevision,
        String route,
        String description) implements AnalysisCandidate {

    public RouteCandidate {
        Objects.requireNonNull(repositoryId, "route candidate repository ID must not be null");
        Objects.requireNonNull(analyzedRevision, "route candidate analyzed revision must not be null");
        Objects.requireNonNull(route, "route candidate route must not be null");
        Objects.requireNonNull(description, "route candidate description must not be null");
        route = route.trim();
        if (route.isBlank() || description.isBlank()) {
            throw new IllegalArgumentException("route candidate route and description must not be blank");
        }
    }

    @Override
    public CandidateKind kind() {
        return CandidateKind.ROUTE;
    }

    @Override
    public Optional<RepositoryRevision> repositoryRevision() {
        return Optional.of(analyzedRevision);
    }
}
