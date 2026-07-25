package com.java.system.agent.analysis.application;

import com.java.system.agent.analysis.domain.AnalysisAttemptId;
import com.java.system.agent.analysis.domain.AnalysisBudget;
import com.java.system.agent.analysis.domain.AnalysisRunId;
import com.java.system.agent.analysis.domain.AnalysisState;
import com.java.system.agent.analysis.domain.AnalysisStatus;
import com.java.system.agent.analysis.domain.ArtifactRef;
import com.java.system.agent.analysis.domain.EvidenceRef;
import com.java.system.agent.analysis.domain.InformationNeed;
import com.java.system.agent.analysis.domain.InformationNeedId;
import com.java.system.agent.analysis.domain.InformationNeedType;
import com.java.system.agent.analysis.domain.RepositoryDiscoverySource;
import com.java.system.agent.analysis.domain.RepositoryId;
import com.java.system.agent.analysis.domain.RepositoryRevision;
import com.java.system.agent.analysis.domain.RepositoryScope;
import com.java.system.agent.analysis.domain.RepositorySelection;
import com.java.system.agent.analysis.domain.SemanticTarget;
import com.java.system.agent.analysis.domain.SemanticTargetKind;

import java.util.List;
import java.util.Optional;

final class GoalStateFixture {

    private GoalStateFixture() {
    }

    static AnalysisState resolved(InformationNeedId needId) {
        AnalysisRunId runId = new AnalysisRunId("run-1");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        AnalysisState state = AnalysisState.initial(runId, attemptId, AnalysisBudget.of(5, 2));
        StateReducer reducer = new DefaultStateReducer();
        RepositoryId repositoryId = new RepositoryId("order-service");
        RepositoryRevision revision = new RepositoryRevision("ord-456");
        RepositoryScope scope = RepositoryScope.of(List.of(new RepositorySelection(
                repositoryId,
                "Selected for goal test",
                true,
                RepositoryDiscoverySource.USER)));
        state = apply(reducer, state, new AnalysisEvent.ScopeResolved(runId, attemptId, 0, scope));
        state = apply(reducer, state, new AnalysisEvent.RevisionPinned(
                runId, attemptId, 1, repositoryId, revision));
        SemanticTarget target = new SemanticTarget(
                SemanticTargetKind.ROUTE,
                "POST /orders",
                Optional.empty());
        InformationNeed need = new InformationNeed(
                needId,
                InformationNeedType.ENTRY_POINT,
                "Locate the order entry point",
                true,
                List.of(repositoryId),
                List.of(target));
        state = apply(reducer, state, new AnalysisEvent.NeedRegistered(runId, attemptId, 2, need));
        EvidenceRef evidence = new EvidenceRef(
                "java-semantic-service",
                repositoryId,
                revision,
                target,
                1.0,
                List.of(),
                new ArtifactRef("sha256:goal-evidence"));
        state = apply(reducer, state, new AnalysisEvent.EvidenceAccepted(
                runId, attemptId, 3, needId, evidence));
        return apply(reducer, state, new AnalysisEvent.NeedResolved(runId, attemptId, 4, needId));
    }

    static AnalysisState withStatus(AnalysisState state, AnalysisStatus status) {
        return new AnalysisState(
                state.runId(),
                state.attemptId(),
                state.stateRevision(),
                status,
                state.repositoryScope(),
                state.revisionVector(),
                state.pendingNeeds(),
                state.resolvedNeedIds(),
                state.evidenceBindings(),
                state.warnings(),
                state.budget());
    }

    private static AnalysisState apply(
            StateReducer reducer,
            AnalysisState state,
            AnalysisEvent event) {
        return reducer.reduce(state, event).candidateState();
    }
}
