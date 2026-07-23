package com.java.system.agent.analysis.application;

import com.java.system.agent.analysis.domain.AnalysisBudget;
import com.java.system.agent.analysis.domain.AnalysisState;
import com.java.system.agent.analysis.domain.AnalysisStatus;
import com.java.system.agent.analysis.domain.AnalysisWarning;
import com.java.system.agent.analysis.domain.AttemptOutcome;
import com.java.system.agent.analysis.domain.EvidenceBinding;
import com.java.system.agent.analysis.domain.EvidenceRef;
import com.java.system.agent.analysis.domain.InformationNeed;
import com.java.system.agent.analysis.domain.InformationNeedId;
import com.java.system.agent.analysis.domain.RepositoryId;
import com.java.system.agent.analysis.domain.RepositoryDiscoverySource;
import com.java.system.agent.analysis.domain.RepositoryScope;
import com.java.system.agent.analysis.domain.RepositorySelection;
import com.java.system.agent.analysis.domain.RevisionVector;
import com.java.system.agent.analysis.port.out.RepositoryDiscovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

public final class DefaultStateReducer implements StateReducer {

    @Override
    public StateTransition reduce(AnalysisState currentState, AnalysisEvent event) {
        Objects.requireNonNull(currentState, "current analysis state must not be null");
        Objects.requireNonNull(event, "analysis event must not be null");
        validateEnvelope(currentState, event);

        AnalysisState candidateState = switch (event) {
            case AnalysisEvent.ScopeResolved scopeResolved -> applyScopeResolved(
                    currentState, scopeResolved);
            case AnalysisEvent.ScopeExpanded scopeExpanded -> applyScopeExpanded(
                    currentState, scopeExpanded);
            case AnalysisEvent.RevisionPinned revisionPinned -> applyRevisionPinned(
                    currentState, revisionPinned);
            case AnalysisEvent.NeedRegistered needRegistered -> applyNeedRegistered(
                    currentState, needRegistered);
            case AnalysisEvent.EvidenceAccepted evidenceAccepted -> applyEvidenceAccepted(
                    currentState, evidenceAccepted);
            case AnalysisEvent.NeedResolved needResolved -> applyNeedResolved(
                    currentState, needResolved);
            case AnalysisEvent.WarningRecorded warningRecorded -> applyWarningRecorded(
                    currentState, warningRecorded);
            case AnalysisEvent.AttemptConcluded attemptConcluded -> applyAttemptConcluded(
                    currentState, attemptConcluded);
        };
        return new StateTransition(event, candidateState);
    }

    private void validateEnvelope(AnalysisState currentState, AnalysisEvent event) {
        if (!currentState.runId().equals(event.runId())) {
            throw new IllegalArgumentException("analysis event belongs to another run");
        }
        if (!currentState.attemptId().equals(event.attemptId())) {
            throw new IllegalArgumentException("analysis event belongs to another attempt");
        }
        if (currentState.stateRevision() != event.expectedStateRevision()) {
            throw new StaleStateRevisionException(
                    event.expectedStateRevision(), currentState.stateRevision());
        }
        if (isTerminal(currentState.status())) {
            throw new IllegalArgumentException("concluded analysis attempt cannot accept events");
        }
    }

    private AnalysisState applyScopeResolved(
            AnalysisState state,
            AnalysisEvent.ScopeResolved event) {
        if (state.repositoryScope().repositoryIds().size() > 0) {
            throw new IllegalArgumentException("repository scope is already resolved");
        }
        return next(
                state,
                AnalysisStatus.REVISION_PINNING,
                event.repositoryScope(),
                state.revisionVector(),
                state.pendingNeeds(),
                state.resolvedNeedIds(),
                state.evidenceBindings(),
                state.warnings(),
                state.budget());
    }

    private AnalysisState applyScopeExpanded(
            AnalysisState state,
            AnalysisEvent.ScopeExpanded event) {
        RepositoryDiscovery discovery = event.repositoryDiscovery();
        EvidenceRef sourceEvidence = discovery.sourceEvidence();
        boolean evidenceAccepted = state.evidenceBindings().stream()
                .anyMatch(binding -> binding.evidenceRef().equals(sourceEvidence));
        if (!evidenceAccepted) {
            throw new IllegalArgumentException(
                    "scope expansion requires previously accepted semantic evidence");
        }
        if (!state.revisionVector().matches(
                sourceEvidence.repositoryId(), sourceEvidence.repositoryRevision())) {
            throw new RevisionMismatchException(
                    sourceEvidence.repositoryId(), sourceEvidence.repositoryRevision());
        }
        RepositorySelection selection = new RepositorySelection(
                discovery.repositoryId(),
                discovery.discoveryReason(),
                event.required(),
                RepositoryDiscoverySource.SEMANTIC_EVIDENCE);
        RepositoryScope expandedScope = state.repositoryScope().expand(selection);
        return next(
                state,
                state.status(),
                expandedScope,
                state.revisionVector(),
                state.pendingNeeds(),
                state.resolvedNeedIds(),
                state.evidenceBindings(),
                state.warnings(),
                state.budget());
    }

    private AnalysisState applyRevisionPinned(
            AnalysisState state,
            AnalysisEvent.RevisionPinned event) {
        RevisionVector pinnedVector = state.revisionVector().pin(
                state.repositoryScope(), event.repositoryId(), event.repositoryRevision());
        return next(
                state,
                state.status(),
                state.repositoryScope(),
                pinnedVector,
                state.pendingNeeds(),
                state.resolvedNeedIds(),
                state.evidenceBindings(),
                state.warnings(),
                state.budget());
    }

    private AnalysisState applyNeedRegistered(
            AnalysisState state,
            AnalysisEvent.NeedRegistered event) {
        SortedMap<InformationNeedId, InformationNeed> pendingNeeds = new TreeMap<>(state.pendingNeeds());
        InformationNeed previous = pendingNeeds.putIfAbsent(
                event.informationNeed().id(), event.informationNeed());
        if (Objects.nonNull(previous) || state.resolvedNeedIds().contains(event.informationNeed().id())) {
            throw new IllegalArgumentException(
                    "information need is already registered: " + event.informationNeed().id().value());
        }
        return next(
                state,
                AnalysisStatus.PLANNING,
                state.repositoryScope(),
                state.revisionVector(),
                pendingNeeds,
                state.resolvedNeedIds(),
                state.evidenceBindings(),
                state.warnings(),
                state.budget());
    }

    private AnalysisState applyEvidenceAccepted(
            AnalysisState state,
            AnalysisEvent.EvidenceAccepted event) {
        InformationNeed informationNeed = requirePendingNeed(state, event.informationNeedId());
        EvidenceRef evidenceRef = event.evidenceRef();
        RepositoryId repositoryId = evidenceRef.repositoryId();
        if (!informationNeed.repositoryCandidates().contains(repositoryId)) {
            throw new IllegalArgumentException(
                    "evidence repository is not a candidate for the information need");
        }
        if (!state.repositoryScope().contains(repositoryId)) {
            throw new IllegalArgumentException("evidence repository is outside the analysis scope");
        }
        if (!state.revisionVector().matches(repositoryId, evidenceRef.repositoryRevision())) {
            throw new RevisionMismatchException(repositoryId, evidenceRef.repositoryRevision());
        }
        EvidenceBinding binding = new EvidenceBinding(event.informationNeedId(), evidenceRef);
        if (state.evidenceBindings().contains(binding)) {
            throw new IllegalArgumentException("evidence is already accepted for this information need");
        }
        List<EvidenceBinding> bindings = new ArrayList<>(state.evidenceBindings());
        bindings.add(binding);
        return next(
                state,
                AnalysisStatus.EXECUTING,
                state.repositoryScope(),
                state.revisionVector(),
                state.pendingNeeds(),
                state.resolvedNeedIds(),
                bindings,
                state.warnings(),
                state.budget());
    }

    private AnalysisState applyNeedResolved(
            AnalysisState state,
            AnalysisEvent.NeedResolved event) {
        requirePendingNeed(state, event.informationNeedId());
        boolean hasEvidence = state.evidenceBindings().stream()
                .anyMatch(binding -> binding.informationNeedId().equals(event.informationNeedId()));
        if (!hasEvidence) {
            throw new IllegalArgumentException("information need cannot resolve without accepted evidence");
        }
        SortedMap<InformationNeedId, InformationNeed> pendingNeeds = new TreeMap<>(state.pendingNeeds());
        pendingNeeds.remove(event.informationNeedId());
        Set<InformationNeedId> resolvedNeedIds = new TreeSet<>(state.resolvedNeedIds());
        resolvedNeedIds.add(event.informationNeedId());
        return next(
                state,
                AnalysisStatus.PLANNING,
                state.repositoryScope(),
                state.revisionVector(),
                pendingNeeds,
                resolvedNeedIds,
                state.evidenceBindings(),
                state.warnings(),
                state.budget());
    }

    private AnalysisState applyWarningRecorded(
            AnalysisState state,
            AnalysisEvent.WarningRecorded event) {
        List<AnalysisWarning> warnings = new ArrayList<>(state.warnings());
        warnings.add(event.warning());
        return next(
                state,
                state.status(),
                state.repositoryScope(),
                state.revisionVector(),
                state.pendingNeeds(),
                state.resolvedNeedIds(),
                state.evidenceBindings(),
                warnings,
                state.budget());
    }

    private AnalysisState applyAttemptConcluded(
            AnalysisState state,
            AnalysisEvent.AttemptConcluded event) {
        return next(
                state,
                terminalStatus(event.outcome()),
                state.repositoryScope(),
                state.revisionVector(),
                state.pendingNeeds(),
                state.resolvedNeedIds(),
                state.evidenceBindings(),
                state.warnings(),
                state.budget());
    }

    private InformationNeed requirePendingNeed(
            AnalysisState state,
            InformationNeedId informationNeedId) {
        InformationNeed informationNeed = state.pendingNeeds().get(informationNeedId);
        if (Objects.isNull(informationNeed)) {
            throw new IllegalArgumentException(
                    "information need is not pending: " + informationNeedId.value());
        }
        return informationNeed;
    }

    private AnalysisStatus terminalStatus(AttemptOutcome outcome) {
        return switch (outcome) {
            case COMPLETED -> AnalysisStatus.COMPLETED;
            case STALE -> AnalysisStatus.STALE;
            case INCONCLUSIVE -> AnalysisStatus.INCONCLUSIVE;
            case FAILED -> AnalysisStatus.FAILED;
            case CANCELLED -> AnalysisStatus.CANCELLED;
        };
    }

    private boolean isTerminal(AnalysisStatus status) {
        return switch (status) {
            case STALE, COMPLETED, INCONCLUSIVE, FAILED, CANCELLED -> true;
            default -> false;
        };
    }

    private AnalysisState next(
            AnalysisState current,
            AnalysisStatus status,
            RepositoryScope repositoryScope,
            RevisionVector revisionVector,
            Map<InformationNeedId, InformationNeed> pendingNeeds,
            Set<InformationNeedId> resolvedNeedIds,
            List<EvidenceBinding> evidenceBindings,
            List<AnalysisWarning> warnings,
            AnalysisBudget budget) {
        return new AnalysisState(
                current.runId(),
                current.attemptId(),
                current.stateRevision() + 1,
                status,
                repositoryScope,
                revisionVector,
                new TreeMap<>(pendingNeeds),
                resolvedNeedIds,
                evidenceBindings,
                warnings,
                budget);
    }
}
