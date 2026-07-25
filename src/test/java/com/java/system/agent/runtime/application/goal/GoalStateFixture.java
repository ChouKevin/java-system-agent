package com.java.system.agent.runtime.application.goal;

import com.java.system.agent.runtime.application.state.AnalysisEvent;
import com.java.system.agent.runtime.application.state.DefaultStateReducer;
import com.java.system.agent.runtime.application.state.StateReducer;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptState;
import com.java.system.agent.runtime.domain.run.AttemptStatus;
import com.java.system.agent.runtime.domain.evidence.ArtifactRef;
import com.java.system.agent.runtime.domain.evidence.EvidenceRef;
import com.java.system.agent.runtime.domain.need.InformationNeed;
import com.java.system.agent.runtime.domain.need.InformationNeedId;
import com.java.system.agent.runtime.domain.need.InformationNeedType;
import com.java.system.agent.runtime.domain.scope.RepositoryDiscoverySource;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.domain.scope.RepositoryScope;
import com.java.system.agent.runtime.domain.scope.RepositorySelection;
import com.java.system.agent.runtime.domain.evidence.SemanticTarget;
import com.java.system.agent.runtime.domain.evidence.SemanticTargetKind;

import java.util.List;
import java.util.Optional;

final class GoalStateFixture {

    private GoalStateFixture() {
    }

    static AttemptState resolved(InformationNeedId needId) {
        AnalysisRunId runId = new AnalysisRunId("run-1");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        AttemptState state = AttemptState.initial(runId, attemptId, AttemptBudget.of(5, 2));
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

    static AttemptState withStatus(AttemptState state, AttemptStatus status) {
        return new AttemptState(
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

    private static AttemptState apply(
            StateReducer reducer,
            AttemptState state,
            AnalysisEvent event) {
        return reducer.reduce(state, event).candidateState();
    }
}
