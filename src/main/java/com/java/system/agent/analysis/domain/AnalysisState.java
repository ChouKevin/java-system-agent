package com.java.system.agent.analysis.domain;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

public record AnalysisState(
        AnalysisRunId runId,
        AnalysisAttemptId attemptId,
        long stateRevision,
        AnalysisStatus status,
        RepositoryScope repositoryScope,
        RevisionVector revisionVector,
        SortedMap<InformationNeedId, InformationNeed> pendingNeeds,
        Set<InformationNeedId> resolvedNeedIds,
        List<EvidenceBinding> evidenceBindings,
        List<AnalysisWarning> warnings,
        AnalysisBudget budget) {

    public AnalysisState {
        Objects.requireNonNull(runId, "analysis run ID must not be null");
        Objects.requireNonNull(attemptId, "analysis attempt ID must not be null");
        Objects.requireNonNull(status, "analysis status must not be null");
        Objects.requireNonNull(repositoryScope, "repository scope must not be null");
        Objects.requireNonNull(revisionVector, "revision vector must not be null");
        Objects.requireNonNull(pendingNeeds, "pending needs must not be null");
        Objects.requireNonNull(resolvedNeedIds, "resolved need IDs must not be null");
        Objects.requireNonNull(evidenceBindings, "evidence bindings must not be null");
        Objects.requireNonNull(warnings, "analysis warnings must not be null");
        Objects.requireNonNull(budget, "analysis budget must not be null");
        if (stateRevision < 0) {
            throw new IllegalArgumentException("state revision must not be negative");
        }
        pendingNeeds = immutableNeeds(pendingNeeds);
        resolvedNeedIds = Collections.unmodifiableSet(new TreeSet<>(resolvedNeedIds));
        evidenceBindings = List.copyOf(evidenceBindings);
        warnings = List.copyOf(warnings);
    }

    public static AnalysisState initial(
            AnalysisRunId runId,
            AnalysisAttemptId attemptId,
            AnalysisBudget budget) {
        return new AnalysisState(
                runId,
                attemptId,
                0,
                AnalysisStatus.RECEIVED,
                RepositoryScope.of(List.of()),
                RevisionVector.empty(),
                Collections.emptySortedMap(),
                Set.of(),
                List.of(),
                List.of(),
                budget);
    }

    public AnalysisState withStateRevision(long newStateRevision) {
        return new AnalysisState(
                runId,
                attemptId,
                newStateRevision,
                status,
                repositoryScope,
                revisionVector,
                pendingNeeds,
                resolvedNeedIds,
                evidenceBindings,
                warnings,
                budget);
    }

    private static SortedMap<InformationNeedId, InformationNeed> immutableNeeds(
            Map<InformationNeedId, InformationNeed> pendingNeeds) {
        SortedMap<InformationNeedId, InformationNeed> copiedNeeds = new TreeMap<>();
        pendingNeeds.forEach((id, need) -> copiedNeeds.put(
                Objects.requireNonNull(id, "pending need ID must not be null"),
                Objects.requireNonNull(need, "pending need must not be null")));
        return Collections.unmodifiableSortedMap(copiedNeeds);
    }
}
