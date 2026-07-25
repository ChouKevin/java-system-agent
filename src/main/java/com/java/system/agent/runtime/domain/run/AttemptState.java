package com.java.system.agent.runtime.domain.run;

import com.java.system.agent.runtime.domain.need.EvidenceBinding;
import com.java.system.agent.runtime.domain.need.InformationNeed;
import com.java.system.agent.runtime.domain.need.InformationNeedId;
import com.java.system.agent.runtime.domain.scope.RepositoryScope;
import com.java.system.agent.runtime.domain.scope.RevisionVector;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Runtime 唯一 authoritative 的執行狀態
 *
 * <p>屬 attempt 範圍：建構子要求 {@code attemptId}，且 revision vector 與預算都必須與該 attempt 一致</p>
 *
 * <p>{@code stateRevision} 只在 {@code DefaultStateReducer#next} 這一個地方遞增，是樂觀鎖
 * 的依據；{@code DefaultStateReducer} 是唯一會依事件轉換既有狀態的類別，{@link #initial}
 * 是這條規則以外的合法例外——它在 attempt 準備階段建立 revision 為零的起始狀態，不經過
 * reduce-and-commit 流程</p>
 */
public record AttemptState(
        AnalysisRunId runId,
        AnalysisAttemptId attemptId,
        long stateRevision,
        AttemptStatus status,
        RepositoryScope repositoryScope,
        RevisionVector revisionVector,
        SortedMap<InformationNeedId, InformationNeed> pendingNeeds,
        Set<InformationNeedId> resolvedNeedIds,
        List<EvidenceBinding> evidenceBindings,
        List<AnalysisWarning> warnings,
        AttemptBudget budget) {

    public AttemptState {
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

    public static AttemptState initial(
            AnalysisRunId runId,
            AnalysisAttemptId attemptId,
            AttemptBudget budget) {
        return new AttemptState(
                runId,
                attemptId,
                0,
                AttemptStatus.RECEIVED,
                RepositoryScope.of(List.of()),
                RevisionVector.empty(),
                Collections.emptySortedMap(),
                Set.of(),
                List.of(),
                List.of(),
                budget);
    }

    public AttemptState withStateRevision(long newStateRevision) {
        return new AttemptState(
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
