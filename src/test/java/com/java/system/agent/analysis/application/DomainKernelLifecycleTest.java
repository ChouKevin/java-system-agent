package com.java.system.agent.analysis.application;

import com.java.system.agent.analysis.domain.AnalysisAttempt;
import com.java.system.agent.analysis.domain.AnalysisAttemptId;
import com.java.system.agent.analysis.domain.AnalysisBudget;
import com.java.system.agent.analysis.domain.AnalysisOutcome;
import com.java.system.agent.analysis.domain.AnalysisRun;
import com.java.system.agent.analysis.domain.AnalysisRunId;
import com.java.system.agent.analysis.domain.AnalysisState;
import com.java.system.agent.analysis.domain.AnalysisStatus;
import com.java.system.agent.analysis.domain.ArtifactRef;
import com.java.system.agent.analysis.domain.AttemptOutcome;
import com.java.system.agent.analysis.domain.EvidenceRef;
import com.java.system.agent.analysis.domain.Goal;
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
import com.java.system.agent.analysis.port.out.RepositoryDiscovery;
import com.java.system.agent.analysis.port.out.SemanticFailure;
import com.java.system.agent.analysis.port.out.SemanticFailureCode;
import com.java.system.agent.analysis.port.out.SemanticQuery;
import com.java.system.agent.analysis.port.out.SemanticQueryResult;
import com.java.system.agent.analysis.port.out.SemanticResultStatus;
import com.java.system.agent.semantic.adapter.fake.FakeSemanticQueryAdapter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainKernelLifecycleTest {

    private final StateReducer reducer = new DefaultStateReducer();
    private final InformationNeedPlanner planner = new InformationNeedPlanner();
    private final GoalEvaluator goalEvaluator = new DefaultGoalEvaluator();

    @Test
    void completesSingleRepositoryLifecycleUsingFakeSemanticPort() {
        AnalysisRunId runId = new AnalysisRunId("run-single");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        RepositoryId repositoryId = new RepositoryId("order-service");
        RepositoryRevision revision = new RepositoryRevision("ord-456");
        InformationNeed need = need("need-order-entry", repositoryId, "POST /orders");
        AnalysisState state = preparedState(runId, attemptId, List.of(repositoryId), List.of(revision));
        state = apply(state, new AnalysisEvent.NeedRegistered(
                runId, attemptId, state.stateRevision(), need));

        PlannedCapability planned = planner.plan(state, need, registry())
                .plannedCapability()
                .orElseThrow();
        SemanticQuery query = query(planned);
        EvidenceRef evidence = evidence(repositoryId, revision, "sha256:order-entry");
        FakeSemanticQueryAdapter semanticPort = new FakeSemanticQueryAdapter().register(
                query,
                new SemanticQueryResult(
                        SemanticResultStatus.SUCCESS,
                        Optional.of(revision),
                        List.of(evidence),
                        List.of(),
                        Optional.empty()));

        SemanticQueryResult result = semanticPort.query(query);
        state = apply(state, new AnalysisEvent.EvidenceAccepted(
                runId, attemptId, state.stateRevision(), need.id(), result.evidence().getFirst()));
        state = apply(state, new AnalysisEvent.NeedResolved(
                runId, attemptId, state.stateRevision(), need.id()));
        GoalEvaluation evaluation = goalEvaluator.evaluate(
                new Goal("Answer the order entry-point question", Set.of(need.id())),
                state,
                Optional.empty());

        assertThat(evaluation.outcome()).contains(AnalysisOutcome.COMPLETED);
        assertThat(state.evidenceBindings()).singleElement()
                .satisfies(binding -> assertThat(binding.evidenceRef()).isEqualTo(evidence));
    }

    @Test
    void completesMultiRepositoryGoalWithRevisionBoundEvidenceFromEachRepository() {
        AnalysisRunId runId = new AnalysisRunId("run-multi");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        RepositoryId orderRepository = new RepositoryId("order-service");
        RepositoryId notificationRepository = new RepositoryId("notification-service");
        RepositoryRevision orderRevision = new RepositoryRevision("ord-456");
        RepositoryRevision notificationRevision = new RepositoryRevision("not-123");
        AnalysisState state = preparedState(
                runId,
                attemptId,
                List.of(orderRepository, notificationRepository),
                List.of(orderRevision, notificationRevision));
        InformationNeed orderNeed = need("need-producer", orderRepository, "OrderCreated producer");
        InformationNeed notificationNeed = need(
                "need-consumer", notificationRepository, "OrderCreated consumer");
        state = resolveWithEvidence(state, orderNeed, evidence(
                orderRepository, orderRevision, "sha256:producer"));
        state = resolveWithEvidence(state, notificationNeed, evidence(
                notificationRepository, notificationRevision, "sha256:consumer"));

        GoalEvaluation evaluation = goalEvaluator.evaluate(
                new Goal(
                        "Explain the cross-service notification flow",
                        Set.of(orderNeed.id(), notificationNeed.id())),
                state,
                Optional.empty());

        assertThat(evaluation.outcome()).contains(AnalysisOutcome.COMPLETED);
        assertThat(state.evidenceBindings()).hasSize(2);
    }

    @Test
    void expandsScopeOnlyFromDiscoveryThatRetainsSemanticEvidence() {
        AnalysisRunId runId = new AnalysisRunId("run-expand");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        RepositoryId orderRepository = new RepositoryId("order-service");
        RepositoryRevision orderRevision = new RepositoryRevision("ord-456");
        AnalysisState state = preparedState(
                runId, attemptId, List.of(orderRepository), List.of(orderRevision));
        EvidenceRef sourceEvidence = evidence(orderRepository, orderRevision, "sha256:cross-link");
        RepositoryDiscovery discovery = new RepositoryDiscovery(
                new RepositoryId("notification-service"),
                "OrderCreated consumer resolved by semantic service",
                sourceEvidence);

        InformationNeed discoveryNeed = need(
                "need-cross-link", orderRepository, "OrderCreated cross-service target");
        state = apply(state, new AnalysisEvent.NeedRegistered(
                runId, attemptId, state.stateRevision(), discoveryNeed));
        state = apply(state, new AnalysisEvent.EvidenceAccepted(
                runId,
                attemptId,
                state.stateRevision(),
                discoveryNeed.id(),
                sourceEvidence));
        state = apply(state, new AnalysisEvent.ScopeExpanded(
                runId, attemptId, state.stateRevision(), discovery, true));
        state = apply(state, new AnalysisEvent.RevisionPinned(
                runId,
                attemptId,
                state.stateRevision(),
                discovery.repositoryId(),
                new RepositoryRevision("not-123")));

        assertThat(state.repositoryScope().repositoryIds()).containsExactly(
                new RepositoryId("notification-service"),
                orderRepository);
        assertThat(discovery.sourceEvidence()).isEqualTo(sourceEvidence);
    }

    @Test
    void revisionMismatchStalesOldAttemptAndRejectsItsEvidenceFromNewAttempt() {
        AnalysisRunId runId = new AnalysisRunId("run-refresh");
        AnalysisAttemptId oldAttemptId = new AnalysisAttemptId("attempt-old");
        AnalysisAttemptId newAttemptId = new AnalysisAttemptId("attempt-new");
        RepositoryId repositoryId = new RepositoryId("order-service");
        RepositoryRevision oldRevision = new RepositoryRevision("ord-456");
        RepositoryRevision newRevision = new RepositoryRevision("ord-789");
        InformationNeed need = need("need-refresh", repositoryId, "POST /orders");
        AnalysisState oldState = preparedState(
                runId, oldAttemptId, List.of(repositoryId), List.of(oldRevision));
        oldState = apply(oldState, new AnalysisEvent.NeedRegistered(
                runId, oldAttemptId, oldState.stateRevision(), need));
        PlannedCapability oldPlan = planner.plan(oldState, need, registry())
                .plannedCapability()
                .orElseThrow();
        SemanticQuery oldQuery = query(oldPlan);
        SemanticQueryResult mismatch = new SemanticQueryResult(
                SemanticResultStatus.REVISION_MISMATCH,
                Optional.of(newRevision),
                List.of(),
                List.of(),
                Optional.of(new SemanticFailure(
                        SemanticFailureCode.REVISION_MISMATCH,
                        "Semantic Service now analyzes a newer revision",
                        true)));
        FakeSemanticQueryAdapter semanticPort = new FakeSemanticQueryAdapter()
                .register(oldQuery, mismatch);

        SemanticQueryResult result = semanticPort.query(oldQuery);
        oldState = apply(oldState, new AnalysisEvent.AttemptConcluded(
                runId, oldAttemptId, oldState.stateRevision(), AttemptOutcome.STALE));
        AnalysisAttempt staleAttempt = AnalysisAttempt.start(
                        oldAttemptId, oldState.revisionVector(), oldState.budget())
                .conclude(AttemptOutcome.STALE);
        AnalysisState newState = preparedState(
                runId, newAttemptId, List.of(repositoryId), List.of(newRevision));
        newState = apply(newState, new AnalysisEvent.NeedRegistered(
                runId, newAttemptId, newState.stateRevision(), need));
        AnalysisAttempt newAttempt = AnalysisAttempt.start(
                newAttemptId, newState.revisionVector(), newState.budget());
        AnalysisRun refreshedRun = AnalysisRun.start(runId, AnalysisAttempt.start(
                        oldAttemptId, oldState.revisionVector(), oldState.budget()))
                .replaceCurrentAttempt(staleAttempt, newAttempt);
        AnalysisState pinnedNewState = newState;
        EvidenceRef oldEvidence = evidence(repositoryId, oldRevision, "sha256:old-entry");

        assertThat(result.status()).isEqualTo(SemanticResultStatus.REVISION_MISMATCH);
        assertThat(oldState.status()).isEqualTo(AnalysisStatus.STALE);
        assertThat(refreshedRun.attempts()).hasSize(2);
        assertThat(newState.revisionVector().matches(repositoryId, newRevision)).isTrue();
        assertThatThrownBy(() -> reducer.reduce(
                pinnedNewState,
                new AnalysisEvent.EvidenceAccepted(
                        runId,
                        newAttemptId,
                        pinnedNewState.stateRevision(),
                        need.id(),
                        oldEvidence)))
                .isInstanceOf(RevisionMismatchException.class);
    }

    @Test
    void keepsPartialAndAmbiguousResultsTypedWhileGoalEndsInconclusive() {
        AnalysisRunId runId = new AnalysisRunId("run-semantic-diagnosis");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        RepositoryId repositoryId = new RepositoryId("order-service");
        RepositoryRevision revision = new RepositoryRevision("ord-456");
        InformationNeed partialNeed = need("need-partial", repositoryId, "POST /orders");
        InformationNeed ambiguousNeed = need(
                "need-ambiguous", repositoryId, "OrderCreated publisher");
        AnalysisState state = preparedState(
                runId, attemptId, List.of(repositoryId), List.of(revision));
        SemanticQuery partialQuery = query(planner.plan(state, partialNeed, registry())
                .plannedCapability()
                .orElseThrow());
        SemanticQuery ambiguousQuery = query(planner.plan(state, ambiguousNeed, registry())
                .plannedCapability()
                .orElseThrow());
        EvidenceRef partialEvidence = evidence(repositoryId, revision, "sha256:partial-entry");
        SemanticQueryResult partialScenario = new SemanticQueryResult(
                SemanticResultStatus.PARTIAL,
                Optional.of(revision),
                List.of(partialEvidence),
                List.of(),
                Optional.of(new SemanticFailure(
                        SemanticFailureCode.PARTIAL_RESULT,
                        "Required dynamic target remains unresolved",
                        false)));
        SemanticQueryResult ambiguousScenario = semanticFailure(
                SemanticResultStatus.AMBIGUOUS,
                SemanticFailureCode.AMBIGUOUS_TARGET,
                "Multiple semantic targets remain");
        FakeSemanticQueryAdapter semanticPort = new FakeSemanticQueryAdapter()
                .register(partialQuery, partialScenario)
                .register(ambiguousQuery, ambiguousScenario);
        SemanticQueryResult partial = semanticPort.query(partialQuery);
        SemanticQueryResult ambiguous = semanticPort.query(ambiguousQuery);

        Goal goal = new Goal(
                "Answer only with complete semantic evidence", Set.of(partialNeed.id()));
        GoalEvaluation partialEvaluation = goalEvaluator.evaluate(
                goal, state, Optional.of(GoalBlocker.PREREQUISITE_MISSING));
        GoalEvaluation ambiguousEvaluation = goalEvaluator.evaluate(
                goal, state, Optional.of(GoalBlocker.PREREQUISITE_MISSING));

        assertThat(partial.status()).isEqualTo(SemanticResultStatus.PARTIAL);
        assertThat(ambiguous.status()).isEqualTo(SemanticResultStatus.AMBIGUOUS);
        assertThat(partialEvaluation.outcome()).contains(AnalysisOutcome.INCONCLUSIVE);
        assertThat(ambiguousEvaluation.outcome()).contains(AnalysisOutcome.INCONCLUSIVE);
    }

    @Test
    void missingCapabilityAndNoProgressProduceInconclusiveOutcomes() {
        RepositoryId repositoryId = new RepositoryId("order-service");
        RepositoryRevision revision = new RepositoryRevision("ord-456");
        InformationNeed need = need("need-missing", repositoryId, "POST /orders");
        AnalysisState state = preparedState(
                new AnalysisRunId("run-no-progress"),
                new AnalysisAttemptId("attempt-1"),
                List.of(repositoryId),
                List.of(revision));
        PlanningResult missing = planner.plan(
                state, need, new SemanticCapabilityRegistry(List.of()));
        ProgressFingerprint fingerprint = ProgressFingerprint.from(state);
        NoProgressEvaluation noProgress = new NoProgressPolicy(2)
                .evaluate(List.of(fingerprint, fingerprint));
        Goal goal = new Goal("Answer the order question", Set.of(need.id()));

        GoalEvaluation missingEvaluation = goalEvaluator.evaluate(
                goal, state, Optional.of(GoalBlocker.CAPABILITY_MISSING));
        GoalEvaluation noProgressEvaluation = goalEvaluator.evaluate(
                goal, state, noProgress.blocker());

        assertThat(missing.status()).isEqualTo(PlanningStatus.CAPABILITY_MISSING);
        assertThat(missingEvaluation.outcome()).contains(AnalysisOutcome.INCONCLUSIVE);
        assertThat(noProgress.terminate()).isTrue();
        assertThat(noProgressEvaluation.outcome()).contains(AnalysisOutcome.INCONCLUSIVE);
    }

    private AnalysisState preparedState(
            AnalysisRunId runId,
            AnalysisAttemptId attemptId,
            List<RepositoryId> repositories,
            List<RepositoryRevision> revisions) {
        AnalysisState state = AnalysisState.initial(runId, attemptId, AnalysisBudget.of(20, 10));
        List<RepositorySelection> selections = repositories.stream()
                .map(repositoryId -> new RepositorySelection(
                        repositoryId,
                        "Selected for lifecycle test",
                        true,
                        RepositoryDiscoverySource.USER))
                .toList();
        state = apply(state, new AnalysisEvent.ScopeResolved(
                runId,
                attemptId,
                state.stateRevision(),
                RepositoryScope.of(selections)));
        for (int index = 0; index < repositories.size(); index++) {
            state = apply(state, new AnalysisEvent.RevisionPinned(
                    runId,
                    attemptId,
                    state.stateRevision(),
                    repositories.get(index),
                    revisions.get(index)));
        }
        return state;
    }

    private AnalysisState resolveWithEvidence(
            AnalysisState state,
            InformationNeed need,
            EvidenceRef evidence) {
        AnalysisState updated = apply(state, new AnalysisEvent.NeedRegistered(
                state.runId(), state.attemptId(), state.stateRevision(), need));
        updated = apply(updated, new AnalysisEvent.EvidenceAccepted(
                updated.runId(), updated.attemptId(), updated.stateRevision(), need.id(), evidence));
        return apply(updated, new AnalysisEvent.NeedResolved(
                updated.runId(), updated.attemptId(), updated.stateRevision(), need.id()));
    }

    private AnalysisState apply(AnalysisState state, AnalysisEvent event) {
        return reducer.reduce(state, event).candidateState();
    }

    private SemanticCapabilityRegistry registry() {
        SemanticCapability capability = new SemanticCapability(
                "entry-point",
                "v1",
                Set.of(InformationNeedType.ENTRY_POINT),
                "semantic-query/v1",
                "semantic-result/v1",
                true,
                true,
                1,
                Duration.ofSeconds(5),
                1);
        return new SemanticCapabilityRegistry(List.of(capability));
    }

    private SemanticQuery query(PlannedCapability planned) {
        return new SemanticQuery(
                planned.capability().qualifiedName(),
                planned.informationNeed(),
                planned.semanticTarget().orElseThrow(),
                planned.repositoryId(),
                planned.expectedRevision());
    }

    private InformationNeed need(String id, RepositoryId repositoryId, String targetKey) {
        return new InformationNeed(
                new InformationNeedId(id),
                InformationNeedType.ENTRY_POINT,
                "Resolve " + targetKey,
                true,
                List.of(repositoryId),
                List.of(new SemanticTarget(
                        SemanticTargetKind.ENTRY_POINT,
                        targetKey,
                        Optional.empty())));
    }

    private EvidenceRef evidence(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            String digest) {
        return new EvidenceRef(
                "java-semantic-service",
                repositoryId,
                revision,
                new SemanticTarget(
                        SemanticTargetKind.SYMBOL,
                        repositoryId.value() + "#resolvedTarget",
                        Optional.empty()),
                1.0,
                List.of(),
                new ArtifactRef(digest));
    }

    private SemanticQueryResult semanticFailure(
            SemanticResultStatus status,
            SemanticFailureCode code,
            String message) {
        return new SemanticQueryResult(
                status,
                Optional.empty(),
                List.of(),
                List.of(),
                Optional.of(new SemanticFailure(code, message, false)));
    }
}
