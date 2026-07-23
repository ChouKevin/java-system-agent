package com.java.system.agent.analysis.port.out;

import com.java.system.agent.analysis.domain.EvidenceRef;
import com.java.system.agent.analysis.domain.RepositoryRevision;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record SemanticQueryResult(
        SemanticResultStatus status,
        Optional<RepositoryRevision> analyzedRevision,
        List<EvidenceRef> evidence,
        List<RepositoryDiscovery> repositoryDiscoveries,
        Optional<SemanticFailure> failure) {

    public SemanticQueryResult {
        Objects.requireNonNull(status, "semantic result status must not be null");
        Objects.requireNonNull(analyzedRevision, "analyzed revision must not be null");
        Objects.requireNonNull(evidence, "semantic evidence must not be null");
        Objects.requireNonNull(repositoryDiscoveries, "repository discoveries must not be null");
        Objects.requireNonNull(failure, "semantic failure must not be null");
        evidence = List.copyOf(evidence);
        repositoryDiscoveries = List.copyOf(repositoryDiscoveries);
        validateShape(status, analyzedRevision, evidence, repositoryDiscoveries, failure);
    }

    private static void validateShape(
            SemanticResultStatus status,
            Optional<RepositoryRevision> analyzedRevision,
            List<EvidenceRef> evidence,
            List<RepositoryDiscovery> repositoryDiscoveries,
            Optional<SemanticFailure> failure) {
        if (status == SemanticResultStatus.SUCCESS || status == SemanticResultStatus.PARTIAL) {
            if (!analyzedRevision.isPresent() || evidence.size() < 1) {
                throw new IllegalArgumentException(
                        "successful or partial semantic result requires revision-bound evidence");
            }
            RepositoryRevision reportedRevision = analyzedRevision.orElseThrow();
            boolean evidenceRevisionMismatch = evidence.stream()
                    .anyMatch(reference -> !reference.repositoryRevision().equals(reportedRevision));
            boolean discoveryRevisionMismatch = repositoryDiscoveries.stream()
                    .map(RepositoryDiscovery::sourceEvidence)
                    .anyMatch(reference -> !reference.repositoryRevision().equals(reportedRevision));
            if (evidenceRevisionMismatch || discoveryRevisionMismatch) {
                throw new IllegalArgumentException(
                        "semantic evidence revision must match the analyzed revision");
            }
            boolean discoveryWithoutReturnedEvidence = repositoryDiscoveries.stream()
                    .map(RepositoryDiscovery::sourceEvidence)
                    .anyMatch(reference -> !evidence.contains(reference));
            if (discoveryWithoutReturnedEvidence) {
                throw new IllegalArgumentException(
                        "repository discovery source must be included in semantic evidence");
            }
            if (status == SemanticResultStatus.SUCCESS && failure.isPresent()) {
                throw new IllegalArgumentException("successful semantic result cannot contain a failure");
            }
            if (status == SemanticResultStatus.PARTIAL && !failure.isPresent()) {
                throw new IllegalArgumentException("partial semantic result requires a normalized failure");
            }
            return;
        }
        if (evidence.size() > 0 || repositoryDiscoveries.size() > 0) {
            throw new IllegalArgumentException("failed semantic result cannot contain accepted evidence");
        }
        if (!failure.isPresent()) {
            throw new IllegalArgumentException("failed semantic result requires a normalized failure");
        }
        if (status == SemanticResultStatus.REVISION_MISMATCH && !analyzedRevision.isPresent()) {
            throw new IllegalArgumentException("revision mismatch must report the analyzed revision");
        }
    }
}
