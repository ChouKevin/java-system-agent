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
        validateAnalyzedRevision(status, analyzedRevision);
        if (status == SemanticResultStatus.SUCCESS || status == SemanticResultStatus.PARTIAL) {
            if (evidence.size() < 1) {
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
            validateFailureCoherence(status, failure);
            return;
        }
        if (evidence.size() > 0 || repositoryDiscoveries.size() > 0) {
            throw new IllegalArgumentException("failed semantic result cannot contain accepted evidence");
        }
        if (!failure.isPresent()) {
            throw new IllegalArgumentException("failed semantic result requires a normalized failure");
        }
        validateFailureCoherence(status, failure);
    }

    private static void validateAnalyzedRevision(
            SemanticResultStatus status,
            Optional<RepositoryRevision> analyzedRevision) {
        boolean requiresAnalyzedRevision = switch (status) {
            case SUCCESS, PARTIAL, REVISION_MISMATCH -> true;
            case AMBIGUOUS, NOT_READY, TIMEOUT, FORBIDDEN, CAPABILITY_MISSING, FAILED -> false;
        };
        if (requiresAnalyzedRevision && analyzedRevision.isEmpty()) {
            throw new IllegalArgumentException(
                    "semantic result status requires an analyzed revision");
        }
        if (!requiresAnalyzedRevision && analyzedRevision.isPresent()) {
            throw new IllegalArgumentException(
                    "semantic result status cannot contain an analyzed revision");
        }
    }

    private static void validateFailureCoherence(
            SemanticResultStatus status,
            Optional<SemanticFailure> failure) {
        if (status == SemanticResultStatus.SUCCESS) {
            return;
        }
        SemanticFailure normalizedFailure = failure.orElseThrow();
        boolean codeMatchesStatus = switch (status) {
            case SUCCESS -> false;
            case PARTIAL -> normalizedFailure.code() == SemanticFailureCode.PARTIAL_RESULT;
            case AMBIGUOUS -> normalizedFailure.code() == SemanticFailureCode.AMBIGUOUS_TARGET;
            case REVISION_MISMATCH -> normalizedFailure.code() == SemanticFailureCode.REVISION_MISMATCH;
            case NOT_READY -> normalizedFailure.code() == SemanticFailureCode.NOT_READY;
            case TIMEOUT -> normalizedFailure.code() == SemanticFailureCode.TIMEOUT;
            case FORBIDDEN -> normalizedFailure.code() == SemanticFailureCode.FORBIDDEN;
            case CAPABILITY_MISSING -> normalizedFailure.code() == SemanticFailureCode.CAPABILITY_MISSING;
            case FAILED -> switch (normalizedFailure.code()) {
                case REPOSITORY_NOT_FOUND, PROTOCOL_ERROR, ENGINE_UNAVAILABLE, ENGINE_FAILURE -> true;
                case PARTIAL_RESULT, AMBIGUOUS_TARGET, REVISION_MISMATCH, NOT_READY, TIMEOUT,
                        FORBIDDEN, CAPABILITY_MISSING -> false;
            };
        };
        if (!codeMatchesStatus) {
            throw new IllegalArgumentException(
                    "semantic result status and failure code are inconsistent");
        }
        boolean terminalFailure = switch (status) {
            case AMBIGUOUS, FORBIDDEN, CAPABILITY_MISSING, FAILED -> true;
            case SUCCESS, PARTIAL, REVISION_MISMATCH, NOT_READY, TIMEOUT -> false;
        };
        if (terminalFailure && normalizedFailure.retryable()) {
            throw new IllegalArgumentException(
                    "terminal semantic result failure must not be retryable");
        }
    }
}
