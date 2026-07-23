package com.java.system.agent.analysis.application;

import com.java.system.agent.analysis.domain.AnalysisState;
import com.java.system.agent.analysis.domain.AnalysisWarning;
import com.java.system.agent.analysis.domain.EvidenceBinding;
import com.java.system.agent.analysis.domain.EvidenceRef;
import com.java.system.agent.analysis.domain.InformationNeed;
import com.java.system.agent.analysis.domain.RepositoryId;
import com.java.system.agent.analysis.domain.RepositoryRevision;
import com.java.system.agent.analysis.port.out.RepositoryDiscovery;
import com.java.system.agent.analysis.port.out.SemanticFailure;
import com.java.system.agent.analysis.port.out.SemanticQueryResult;
import com.java.system.agent.analysis.port.out.SemanticResultStatus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class SemanticResultHandler {

    private final TransitionCommitter transitionCommitter;

    public SemanticResultHandler(TransitionCommitter transitionCommitter) {
        this.transitionCommitter = Objects.requireNonNull(
                transitionCommitter, "transition committer must not be null");
    }

    public SemanticStepResult handle(
            AnalysisState state,
            InformationNeed pendingNeed,
            SemanticQueryResult result,
            boolean retryAllowed) {
        Objects.requireNonNull(state, "analysis state must not be null");
        Objects.requireNonNull(pendingNeed, "pending information need must not be null");
        Objects.requireNonNull(result, "semantic query result must not be null");
        requireExactPendingNeed(state, pendingNeed);

        return switch (result.status()) {
            case SUCCESS -> handleSuccess(state, pendingNeed, result);
            case PARTIAL -> handlePartial(state, pendingNeed, result);
            case REVISION_MISMATCH -> step(state, SemanticStepDisposition.STALE, result.failure());
            case NOT_READY, TIMEOUT -> handleTransient(state, result, retryAllowed);
            case AMBIGUOUS -> block(state, result, "SEMANTIC_AMBIGUOUS_TARGET");
            case FORBIDDEN -> block(state, result, "SEMANTIC_FORBIDDEN");
            case CAPABILITY_MISSING -> block(state, result, "SEMANTIC_CAPABILITY_MISSING");
            case FAILED -> fail(state, result);
        };
    }

    private SemanticStepResult handleSuccess(
            AnalysisState state,
            InformationNeed pendingNeed,
            SemanticQueryResult result) {
        Optional<ValidatedEvidence> validated = validateEvidenceAndDiscoveries(state, pendingNeed, result);
        if (validated.isEmpty()) {
            return step(state, SemanticStepDisposition.STALE, result.failure());
        }
        AppliedEvidence applied = applyEvidenceAndDiscoveries(state, pendingNeed, validated.orElseThrow());
        AnalysisState resolved = commit(applied.state(), new AnalysisEvent.NeedResolved(
                applied.state().runId(),
                applied.state().attemptId(),
                applied.state().stateRevision(),
                pendingNeed.id()));
        return new SemanticStepResult(
                resolved, SemanticStepDisposition.PROGRESSED, Optional.empty(), applied.discoveries());
    }

    private SemanticStepResult handlePartial(
            AnalysisState state,
            InformationNeed pendingNeed,
            SemanticQueryResult result) {
        Optional<ValidatedEvidence> validated = validateEvidenceAndDiscoveries(state, pendingNeed, result);
        if (validated.isEmpty()) {
            return step(state, SemanticStepDisposition.STALE, result.failure());
        }
        AppliedEvidence applied = applyEvidenceAndDiscoveries(state, pendingNeed, validated.orElseThrow());
        AnalysisState warned = recordWarningIfAbsent(
                applied.state(), "SEMANTIC_PARTIAL_RESULT", result.failure().orElseThrow());
        return new SemanticStepResult(
                warned, SemanticStepDisposition.PARTIAL, result.failure(), applied.discoveries());
    }

    private SemanticStepResult handleTransient(
            AnalysisState state,
            SemanticQueryResult result,
            boolean retryAllowed) {
        if (retryAllowed) {
            return step(state, SemanticStepDisposition.RETRYABLE, result.failure());
        }
        String warningCode = switch (result.status()) {
            case NOT_READY -> "SEMANTIC_NOT_READY";
            case TIMEOUT -> "SEMANTIC_TIMEOUT";
            default -> throw new IllegalArgumentException("semantic status is not transient");
        };
        return block(state, result, warningCode);
    }

    private SemanticStepResult block(
            AnalysisState state,
            SemanticQueryResult result,
            String warningCode) {
        AnalysisState warned = recordWarningIfAbsent(state, warningCode, result.failure().orElseThrow());
        return step(warned, SemanticStepDisposition.BLOCKED, result.failure());
    }

    private SemanticStepResult fail(AnalysisState state, SemanticQueryResult result) {
        SemanticFailure failure = result.failure().orElseThrow();
        AnalysisState warned = recordWarningIfAbsent(
                state, "SEMANTIC_" + failure.code().name(), failure);
        return step(warned, SemanticStepDisposition.FAILED, result.failure());
    }

    private Optional<ValidatedEvidence> validateEvidenceAndDiscoveries(
            AnalysisState state,
            InformationNeed pendingNeed,
            SemanticQueryResult result) {
        Optional<RepositoryRevision> analyzedRevision = result.analyzedRevision();
        if (analyzedRevision.isEmpty()) {
            return Optional.empty();
        }
        Set<EvidenceRef> returnedEvidence = new LinkedHashSet<>(result.evidence());
        List<EvidenceRef> newEvidence = new ArrayList<>();
        for (EvidenceRef evidence : result.evidence()) {
            if (!evidence.repositoryRevision().equals(analyzedRevision.orElseThrow())
                    || !pendingNeed.repositoryCandidates().contains(evidence.repositoryId())
                    || !state.repositoryScope().contains(evidence.repositoryId())
                    || !state.revisionVector().matches(evidence.repositoryId(), evidence.repositoryRevision())) {
                return Optional.empty();
            }
            if (!isAcceptedForNeed(state, pendingNeed, evidence) && !newEvidence.contains(evidence)) {
                newEvidence.add(evidence);
            }
        }
        Set<EvidenceRef> acceptedOrNewEvidence = new LinkedHashSet<>(newEvidence);
        for (EvidenceBinding binding : state.evidenceBindings()) {
            if (binding.informationNeedId().equals(pendingNeed.id())) {
                acceptedOrNewEvidence.add(binding.evidenceRef());
            }
        }
        List<RepositoryDiscovery> newDiscoveries = new ArrayList<>();
        Set<RepositoryId> discoveredRepositories = new LinkedHashSet<>();
        for (RepositoryDiscovery discovery : result.repositoryDiscoveries()) {
            if (!returnedEvidence.contains(discovery.sourceEvidence())
                    || !acceptedOrNewEvidence.contains(discovery.sourceEvidence())) {
                return Optional.empty();
            }
            if (!state.repositoryScope().contains(discovery.repositoryId())
                    && discoveredRepositories.add(discovery.repositoryId())) {
                newDiscoveries.add(discovery);
            }
        }
        return Optional.of(new ValidatedEvidence(newEvidence, newDiscoveries));
    }

    private AppliedEvidence applyEvidenceAndDiscoveries(
            AnalysisState state,
            InformationNeed pendingNeed,
            ValidatedEvidence validated) {
        AnalysisState current = state;
        for (EvidenceRef evidence : validated.newEvidence()) {
            current = commit(current, new AnalysisEvent.EvidenceAccepted(
                    current.runId(), current.attemptId(), current.stateRevision(), pendingNeed.id(), evidence));
        }
        for (RepositoryDiscovery discovery : validated.newDiscoveries()) {
            current = commit(current, new AnalysisEvent.ScopeExpanded(
                    current.runId(), current.attemptId(), current.stateRevision(), discovery, false));
        }
        return new AppliedEvidence(current, validated.newDiscoveries());
    }

    private AnalysisState recordWarningIfAbsent(
            AnalysisState state,
            String warningCode,
            SemanticFailure failure) {
        boolean warningAlreadyRecorded = state.warnings().stream()
                .anyMatch(warning -> warning.code().equals(warningCode));
        if (warningAlreadyRecorded) {
            return state;
        }
        return commit(state, new AnalysisEvent.WarningRecorded(
                state.runId(),
                state.attemptId(),
                state.stateRevision(),
                new AnalysisWarning(warningCode, failure.message())));
    }

    private boolean isAcceptedForNeed(
            AnalysisState state,
            InformationNeed pendingNeed,
            EvidenceRef evidence) {
        return state.evidenceBindings().contains(new EvidenceBinding(pendingNeed.id(), evidence));
    }

    private void requireExactPendingNeed(AnalysisState state, InformationNeed pendingNeed) {
        Optional<InformationNeed> registeredNeed = Optional.ofNullable(state.pendingNeeds().get(pendingNeed.id()));
        if (registeredNeed.filter(pendingNeed::equals).isEmpty()) {
            throw new IllegalArgumentException("information need is not the exact pending need value");
        }
    }

    private SemanticStepResult step(
            AnalysisState state,
            SemanticStepDisposition disposition,
            Optional<SemanticFailure> failure) {
        return new SemanticStepResult(state, disposition, failure, List.of());
    }

    private AnalysisState commit(AnalysisState state, AnalysisEvent event) {
        return transitionCommitter.apply(state, event);
    }

    private record ValidatedEvidence(
            List<EvidenceRef> newEvidence,
            List<RepositoryDiscovery> newDiscoveries) {

        private ValidatedEvidence {
            newEvidence = List.copyOf(newEvidence);
            newDiscoveries = List.copyOf(newDiscoveries);
        }
    }

    private record AppliedEvidence(AnalysisState state, List<RepositoryDiscovery> discoveries) {

        private AppliedEvidence {
            Objects.requireNonNull(state, "analysis state must not be null");
            discoveries = List.copyOf(discoveries);
        }
    }
}
