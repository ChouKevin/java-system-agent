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
import com.java.system.agent.analysis.port.out.SemanticFailureCode;
import com.java.system.agent.analysis.port.out.SemanticQuery;
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
            SemanticQuery query,
            SemanticQueryResult result,
            boolean retryAllowed) {
        Objects.requireNonNull(state, "analysis state must not be null");
        Objects.requireNonNull(query, "semantic query must not be null");
        Objects.requireNonNull(result, "semantic query result must not be null");
        InformationNeed pendingNeed = query.informationNeed();
        requireExactPendingNeed(state, pendingNeed);
        requireQueryBoundToState(state, query);

        return switch (result.status()) {
            case SUCCESS -> handleSuccess(state, query, result);
            case PARTIAL -> handlePartial(state, query, result);
            case REVISION_MISMATCH -> handleRevisionMismatch(state, query, result);
            case NOT_READY, TIMEOUT -> handleTransient(state, result, retryAllowed);
            case AMBIGUOUS -> block(state, result, "SEMANTIC_AMBIGUOUS_TARGET");
            case FORBIDDEN -> block(state, result, "SEMANTIC_FORBIDDEN");
            case CAPABILITY_MISSING -> block(state, result, "SEMANTIC_CAPABILITY_MISSING");
            case FAILED -> fail(state, result);
        };
    }

    private SemanticStepResult handleSuccess(
            AnalysisState state,
            SemanticQuery query,
            SemanticQueryResult result) {
        InformationNeed pendingNeed = query.informationNeed();
        EvidenceValidation validation = validateEvidenceAndDiscoveries(state, query, result);
        if (validation.outcome() == EvidenceValidationOutcome.STALE) {
            return step(state, SemanticStepDisposition.STALE, result.failure());
        }
        if (validation.outcome() == EvidenceValidationOutcome.PROTOCOL_ERROR) {
            return protocolFailure(state);
        }
        AppliedEvidence applied = applyEvidenceAndDiscoveries(
                state, pendingNeed, validation.validatedEvidence().orElseThrow());
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
            SemanticQuery query,
            SemanticQueryResult result) {
        InformationNeed pendingNeed = query.informationNeed();
        EvidenceValidation validation = validateEvidenceAndDiscoveries(state, query, result);
        if (validation.outcome() == EvidenceValidationOutcome.STALE) {
            return step(state, SemanticStepDisposition.STALE, result.failure());
        }
        if (validation.outcome() == EvidenceValidationOutcome.PROTOCOL_ERROR) {
            return protocolFailure(state);
        }
        AppliedEvidence applied = applyEvidenceAndDiscoveries(
                state, pendingNeed, validation.validatedEvidence().orElseThrow());
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

    private SemanticStepResult handleRevisionMismatch(
            AnalysisState state,
            SemanticQuery query,
            SemanticQueryResult result) {
        RepositoryRevision analyzedRevision = result.analyzedRevision().orElseThrow();
        if (analyzedRevision.equals(query.expectedRevision())) {
            return protocolFailure(state);
        }
        return step(state, SemanticStepDisposition.STALE, result.failure());
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

    private SemanticStepResult protocolFailure(AnalysisState state) {
        SemanticFailure failure = new SemanticFailure(
                SemanticFailureCode.PROTOCOL_ERROR,
                "semantic result violates the revision-bound evidence contract",
                false);
        AnalysisState warned = recordWarningIfAbsent(
                state, "SEMANTIC_PROTOCOL_ERROR", failure);
        return step(warned, SemanticStepDisposition.FAILED, Optional.of(failure));
    }

    private EvidenceValidation validateEvidenceAndDiscoveries(
            AnalysisState state,
            SemanticQuery query,
            SemanticQueryResult result) {
        InformationNeed pendingNeed = query.informationNeed();
        Optional<RepositoryRevision> analyzedRevision = result.analyzedRevision();
        if (analyzedRevision.isEmpty()) {
            return EvidenceValidation.protocolError();
        }
        boolean revisionMismatch = !analyzedRevision.orElseThrow().equals(query.expectedRevision());
        Set<EvidenceRef> returnedEvidence = new LinkedHashSet<>(result.evidence());
        List<EvidenceRef> newEvidence = new ArrayList<>();
        for (EvidenceRef evidence : result.evidence()) {
            if (!evidence.repositoryRevision().equals(analyzedRevision.orElseThrow())
                    || !evidence.repositoryId().equals(query.repositoryId())
                    || !pendingNeed.repositoryCandidates().contains(evidence.repositoryId())
                    || !state.repositoryScope().contains(evidence.repositoryId())) {
                return EvidenceValidation.protocolError();
            }
            if (!state.revisionVector().matches(evidence.repositoryId(), evidence.repositoryRevision())) {
                revisionMismatch = true;
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
                return EvidenceValidation.protocolError();
            }
            if (!state.repositoryScope().contains(discovery.repositoryId())
                    && discoveredRepositories.add(discovery.repositoryId())) {
                newDiscoveries.add(discovery);
            }
        }
        if (revisionMismatch) {
            return EvidenceValidation.stale();
        }
        return EvidenceValidation.valid(new ValidatedEvidence(newEvidence, newDiscoveries));
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

    private void requireQueryBoundToState(AnalysisState state, SemanticQuery query) {
        if (!query.informationNeed().repositoryCandidates().contains(query.repositoryId())) {
            throw new IllegalArgumentException(
                    "semantic query repository is not a candidate for the pending need");
        }
        if (!state.repositoryScope().contains(query.repositoryId())) {
            throw new IllegalArgumentException(
                    "semantic query repository is outside the analysis scope");
        }
        if (!state.revisionVector().matches(query.repositoryId(), query.expectedRevision())) {
            throw new IllegalArgumentException(
                    "semantic query expected revision is not pinned in the analysis state");
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

    private enum EvidenceValidationOutcome {
        VALID,
        STALE,
        PROTOCOL_ERROR
    }

    private record EvidenceValidation(
            EvidenceValidationOutcome outcome,
            Optional<ValidatedEvidence> validatedEvidence) {

        private EvidenceValidation {
            Objects.requireNonNull(outcome, "evidence validation outcome must not be null");
            Objects.requireNonNull(validatedEvidence, "validated evidence must not be null");
            if ((outcome == EvidenceValidationOutcome.VALID) != validatedEvidence.isPresent()) {
                throw new IllegalArgumentException(
                        "valid evidence outcome requires exactly one validated evidence value");
            }
        }

        private static EvidenceValidation valid(ValidatedEvidence validatedEvidence) {
            return new EvidenceValidation(
                    EvidenceValidationOutcome.VALID,
                    Optional.of(Objects.requireNonNull(
                            validatedEvidence, "validated evidence must not be null")));
        }

        private static EvidenceValidation stale() {
            return new EvidenceValidation(EvidenceValidationOutcome.STALE, Optional.empty());
        }

        private static EvidenceValidation protocolError() {
            return new EvidenceValidation(EvidenceValidationOutcome.PROTOCOL_ERROR, Optional.empty());
        }
    }

    private record AppliedEvidence(AnalysisState state, List<RepositoryDiscovery> discoveries) {

        private AppliedEvidence {
            Objects.requireNonNull(state, "analysis state must not be null");
            discoveries = List.copyOf(discoveries);
        }
    }
}
