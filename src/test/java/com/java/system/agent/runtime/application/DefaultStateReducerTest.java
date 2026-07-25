package com.java.system.agent.runtime.application;

import com.java.system.agent.runtime.domain.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.AnalysisBudget;
import com.java.system.agent.runtime.domain.AnalysisRunId;
import com.java.system.agent.runtime.domain.AnalysisState;
import com.java.system.agent.runtime.domain.AnalysisStatus;
import com.java.system.agent.runtime.domain.AnalysisWarning;
import com.java.system.agent.runtime.domain.ArtifactRef;
import com.java.system.agent.runtime.domain.AttemptOutcome;
import com.java.system.agent.runtime.domain.EvidenceRef;
import com.java.system.agent.runtime.domain.InformationNeed;
import com.java.system.agent.runtime.domain.InformationNeedId;
import com.java.system.agent.runtime.domain.InformationNeedType;
import com.java.system.agent.runtime.domain.RepositoryDiscoverySource;
import com.java.system.agent.runtime.domain.RepositoryId;
import com.java.system.agent.runtime.domain.RepositoryRevision;
import com.java.system.agent.runtime.domain.RepositoryScope;
import com.java.system.agent.runtime.domain.RepositorySelection;
import com.java.system.agent.runtime.domain.SemanticTarget;
import com.java.system.agent.runtime.domain.SemanticTargetKind;
import com.java.system.agent.runtime.port.out.RepositoryDiscovery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultStateReducerTest {

    private final StateReducer reducer = new DefaultStateReducer();

    @Test
    void rejectsStaleExpectedStateRevision() {
        AnalysisState state = initialState();
        AnalysisEvent event = new AnalysisEvent.ScopeResolved(
                runId(), attemptId(), 1, scope("order-service"));

        assertThatThrownBy(() -> reducer.reduce(state, event))
                .isInstanceOf(StaleStateRevisionException.class);
    }

    @Test
    void rejectsEventForAnotherAttempt() {
        AnalysisState state = initialState();
        AnalysisEvent event = new AnalysisEvent.ScopeResolved(
                runId(), new AnalysisAttemptId("attempt-other"), 0, scope("order-service"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> reducer.reduce(state, event))
                .withMessageContaining("attempt");
    }

    @Test
    void scopesPinsRegistersAcceptsAndResolvesNeed() {
        AnalysisState state = initialState();
        state = apply(state, new AnalysisEvent.ScopeResolved(
                runId(), attemptId(), 0, scope("order-service")));
        state = apply(state, new AnalysisEvent.RevisionPinned(
                runId(), attemptId(), 1,
                new RepositoryId("order-service"), new RepositoryRevision("ord-456")));
        InformationNeed need = need();
        state = apply(state, new AnalysisEvent.NeedRegistered(
                runId(), attemptId(), 2, need));
        EvidenceRef evidence = evidence("ord-456");
        state = apply(state, new AnalysisEvent.EvidenceAccepted(
                runId(), attemptId(), 3, need.id(), evidence));
        state = apply(state, new AnalysisEvent.NeedResolved(
                runId(), attemptId(), 4, need.id()));

        assertThat(state.stateRevision()).isEqualTo(5);
        assertThat(state.pendingNeeds()).isEmpty();
        assertThat(state.resolvedNeedIds()).containsExactly(need.id());
        assertThat(state.evidenceBindings()).singleElement()
                .satisfies(binding -> assertThat(binding.evidenceRef()).isEqualTo(evidence));
    }

    @Test
    void rejectsEvidenceFromDifferentRevision() {
        AnalysisState state = initialState();
        state = apply(state, new AnalysisEvent.ScopeResolved(
                runId(), attemptId(), 0, scope("order-service")));
        state = apply(state, new AnalysisEvent.RevisionPinned(
                runId(), attemptId(), 1,
                new RepositoryId("order-service"), new RepositoryRevision("ord-456")));
        InformationNeed need = need();
        state = apply(state, new AnalysisEvent.NeedRegistered(
                runId(), attemptId(), 2, need));
        AnalysisState pinnedState = state;

        assertThatThrownBy(() -> reducer.reduce(
                pinnedState,
                new AnalysisEvent.EvidenceAccepted(
                        runId(), attemptId(), 3, need.id(), evidence("ord-789"))))
                .isInstanceOf(RevisionMismatchException.class);
        assertThat(pinnedState.evidenceBindings()).isEmpty();
    }

    @Test
    void rejectsEvidenceFromRepositoryOutsideTheInformationNeedCandidates() {
        AnalysisState state = initialState();
        RepositoryScope multiRepositoryScope = RepositoryScope.of(List.of(
                new RepositorySelection(
                        new RepositoryId("order-service"),
                        "Question entry point",
                        true,
                        RepositoryDiscoverySource.USER),
                new RepositorySelection(
                        new RepositoryId("notification-service"),
                        "Related service",
                        true,
                        RepositoryDiscoverySource.USER)));
        state = apply(state, new AnalysisEvent.ScopeResolved(
                runId(), attemptId(), 0, multiRepositoryScope));
        state = apply(state, new AnalysisEvent.RevisionPinned(
                runId(), attemptId(), 1,
                new RepositoryId("order-service"), new RepositoryRevision("shared-revision")));
        state = apply(state, new AnalysisEvent.RevisionPinned(
                runId(), attemptId(), 2,
                new RepositoryId("notification-service"), new RepositoryRevision("shared-revision")));
        InformationNeed orderNeed = need();
        state = apply(state, new AnalysisEvent.NeedRegistered(
                runId(), attemptId(), 3, orderNeed));
        EvidenceRef notificationEvidence = new EvidenceRef(
                "java-semantic-service",
                new RepositoryId("notification-service"),
                new RepositoryRevision("shared-revision"),
                new SemanticTarget(
                        SemanticTargetKind.SYMBOL,
                        "com.example.NotificationConsumer#consume",
                        Optional.empty()),
                1.0,
                List.of(),
                new ArtifactRef("sha256:wrong-repository"));
        AnalysisState pinnedState = state;

        assertThatIllegalArgumentException()
                .isThrownBy(() -> reducer.reduce(
                        pinnedState,
                        new AnalysisEvent.EvidenceAccepted(
                                runId(),
                                attemptId(),
                                pinnedState.stateRevision(),
                                orderNeed.id(),
                                notificationEvidence)))
                .withMessageContaining("candidate");
    }

    @Test
    void expandsScopeBeforePinningDiscoveredRepository() {
        AnalysisState state = initialState();
        state = apply(state, new AnalysisEvent.ScopeResolved(
                runId(), attemptId(), 0, scope("order-service")));
        state = apply(state, new AnalysisEvent.RevisionPinned(
                runId(), attemptId(), 1,
                new RepositoryId("order-service"), new RepositoryRevision("ord-456")));
        InformationNeed sourceNeed = need();
        EvidenceRef sourceEvidence = evidence("ord-456");
        state = apply(state, new AnalysisEvent.NeedRegistered(
                runId(), attemptId(), 2, sourceNeed));
        state = apply(state, new AnalysisEvent.EvidenceAccepted(
                runId(), attemptId(), 3, sourceNeed.id(), sourceEvidence));
        RepositoryDiscovery discovery = new RepositoryDiscovery(
                new RepositoryId("notification-service"),
                "Semantic evidence found the OrderCreated consumer",
                sourceEvidence);
        state = apply(state, new AnalysisEvent.ScopeExpanded(
                runId(), attemptId(), 4, discovery, true));
        state = apply(state, new AnalysisEvent.RevisionPinned(
                runId(), attemptId(), 5,
                discovery.repositoryId(), new RepositoryRevision("not-123")));

        assertThat(state.repositoryScope().repositoryIds()).containsExactly(
                new RepositoryId("notification-service"),
                new RepositoryId("order-service"));
        assertThat(state.revisionVector().matches(
                discovery.repositoryId(), new RepositoryRevision("not-123"))).isTrue();
    }

    @Test
    void recordsWarningAndTerminalAttemptStatus() {
        AnalysisState state = initialState();
        AnalysisWarning warning = new AnalysisWarning("PARTIAL_RESULT", "Some calls remain unresolved");
        state = apply(state, new AnalysisEvent.WarningRecorded(
                runId(), attemptId(), 0, warning));
        state = apply(state, new AnalysisEvent.AttemptConcluded(
                runId(), attemptId(), 1, AttemptOutcome.STALE));

        assertThat(state.warnings()).containsExactly(warning);
        assertThat(state.status()).isEqualTo(AnalysisStatus.STALE);
    }

    @Test
    void rejectsEventsAfterAttemptHasConcluded() {
        AnalysisState state = initialState();
        state = apply(state, new AnalysisEvent.AttemptConcluded(
                runId(), attemptId(), 0, AttemptOutcome.INCONCLUSIVE));
        AnalysisState terminalState = state;

        assertThatIllegalArgumentException()
                .isThrownBy(() -> reducer.reduce(
                        terminalState,
                        new AnalysisEvent.WarningRecorded(
                                runId(),
                                attemptId(),
                                terminalState.stateRevision(),
                                new AnalysisWarning("LATE", "Must not mutate terminal state"))))
                .withMessageContaining("concluded");
    }

    @Test
    void rejectsScopeExpansionWithoutPreviouslyAcceptedSourceEvidence() {
        AnalysisState state = initialState();
        state = apply(state, new AnalysisEvent.ScopeResolved(
                runId(), attemptId(), 0, scope("order-service")));
        state = apply(state, new AnalysisEvent.RevisionPinned(
                runId(), attemptId(), 1,
                new RepositoryId("order-service"), new RepositoryRevision("ord-456")));
        RepositoryDiscovery forgedDiscovery = new RepositoryDiscovery(
                new RepositoryId("notification-service"),
                "Claims semantic discovery without accepted source evidence",
                evidence("ord-456"));
        AnalysisState pinnedState = state;

        assertThatIllegalArgumentException()
                .isThrownBy(() -> reducer.reduce(
                        pinnedState,
                        new AnalysisEvent.ScopeExpanded(
                                runId(),
                                attemptId(),
                                pinnedState.stateRevision(),
                                forgedDiscovery,
                                true)))
                .withMessageContaining("evidence");
    }

    @Test
    void consumesOnlyOneStepForRevisionProbe() {
        AnalysisState state = initialState();

        AnalysisState updated = apply(state, new AnalysisEvent.BudgetConsumed(
                runId(),
                attemptId(),
                state.stateRevision(),
                AnalysisBudgetActivity.REVISION_PROBE));

        assertThat(updated.budget().usedSteps()).isEqualTo(1);
        assertThat(updated.budget().usedSemanticCalls()).isZero();
        assertThat(updated.status()).isEqualTo(AnalysisStatus.RECEIVED);
    }

    @Test
    void consumesStepAndSemanticCallForSemanticQueryAndRetry() {
        AnalysisState state = initialState();
        state = apply(state, new AnalysisEvent.BudgetConsumed(
                runId(),
                attemptId(),
                state.stateRevision(),
                AnalysisBudgetActivity.SEMANTIC_QUERY));
        state = apply(state, new AnalysisEvent.BudgetConsumed(
                runId(),
                attemptId(),
                state.stateRevision(),
                AnalysisBudgetActivity.SEMANTIC_RETRY));

        assertThat(state.budget().usedSteps()).isEqualTo(2);
        assertThat(state.budget().usedSemanticCalls()).isEqualTo(2);
        assertThat(state.status()).isEqualTo(AnalysisStatus.EXECUTING);
    }

    @Test
    void rejectsBudgetConsumptionAfterConfiguredBudgetIsExhausted() {
        AnalysisState state = AnalysisState.initial(runId(), attemptId(), AnalysisBudget.of(1, 2));
        state = apply(state, new AnalysisEvent.BudgetConsumed(
                runId(),
                attemptId(),
                state.stateRevision(),
                AnalysisBudgetActivity.REVISION_PROBE));
        AnalysisState exhaustedState = state;

        assertThatIllegalArgumentException()
                .isThrownBy(() -> reducer.reduce(
                        exhaustedState,
                        new AnalysisEvent.BudgetConsumed(
                                runId(),
                                attemptId(),
                                exhaustedState.stateRevision(),
                                AnalysisBudgetActivity.SEMANTIC_QUERY)))
                .withMessageContaining("budget");
    }

    @Test
    void rejectsSemanticRetryWhenSemanticBudgetIsExhaustedBeforeStepBudget() {
        AnalysisState state = AnalysisState.initial(runId(), attemptId(), AnalysisBudget.of(2, 1));
        state = apply(state, new AnalysisEvent.BudgetConsumed(
                runId(),
                attemptId(),
                state.stateRevision(),
                AnalysisBudgetActivity.SEMANTIC_QUERY));
        AnalysisState semanticBudgetExhaustedState = state;

        assertThat(semanticBudgetExhaustedState.budget().hasStepRemaining()).isTrue();
        assertThat(semanticBudgetExhaustedState.budget().hasSemanticCallRemaining()).isFalse();
        long stateRevisionBeforeRejectedRetry = semanticBudgetExhaustedState.stateRevision();
        int maxStepsBeforeRejectedRetry = semanticBudgetExhaustedState.budget().maxSteps();
        int usedStepsBeforeRejectedRetry = semanticBudgetExhaustedState.budget().usedSteps();
        int maxSemanticCallsBeforeRejectedRetry = semanticBudgetExhaustedState.budget().maxSemanticCalls();
        int usedSemanticCallsBeforeRejectedRetry = semanticBudgetExhaustedState.budget().usedSemanticCalls();
        AnalysisStatus statusBeforeRejectedRetry = semanticBudgetExhaustedState.status();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> reducer.reduce(
                        semanticBudgetExhaustedState,
                        new AnalysisEvent.BudgetConsumed(
                                runId(),
                                attemptId(),
                                semanticBudgetExhaustedState.stateRevision(),
                                AnalysisBudgetActivity.SEMANTIC_RETRY)))
                .withMessageContaining("semantic call budget")
                .withMessageNotContaining("step budget");
        assertThat(semanticBudgetExhaustedState.stateRevision())
                .isEqualTo(stateRevisionBeforeRejectedRetry);
        assertThat(semanticBudgetExhaustedState.budget().maxSteps())
                .isEqualTo(maxStepsBeforeRejectedRetry);
        assertThat(semanticBudgetExhaustedState.budget().usedSteps())
                .isEqualTo(usedStepsBeforeRejectedRetry);
        assertThat(semanticBudgetExhaustedState.budget().maxSemanticCalls())
                .isEqualTo(maxSemanticCallsBeforeRejectedRetry);
        assertThat(semanticBudgetExhaustedState.budget().usedSemanticCalls())
                .isEqualTo(usedSemanticCallsBeforeRejectedRetry);
        assertThat(semanticBudgetExhaustedState.status()).isEqualTo(statusBeforeRejectedRetry);
    }

    private AnalysisState apply(AnalysisState state, AnalysisEvent event) {
        return reducer.reduce(state, event).candidateState();
    }

    private AnalysisState initialState() {
        return AnalysisState.initial(runId(), attemptId(), AnalysisBudget.of(10, 5));
    }

    private AnalysisRunId runId() {
        return new AnalysisRunId("run-1");
    }

    private AnalysisAttemptId attemptId() {
        return new AnalysisAttemptId("attempt-1");
    }

    private RepositoryScope scope(String repositoryId) {
        return RepositoryScope.of(List.of(new RepositorySelection(
                new RepositoryId(repositoryId),
                "Selected for reducer test",
                true,
                RepositoryDiscoverySource.USER)));
    }

    private InformationNeed need() {
        return new InformationNeed(
                new InformationNeedId("need-entry-point"),
                InformationNeedType.ENTRY_POINT,
                "Locate the order entry point",
                true,
                List.of(new RepositoryId("order-service")),
                List.of(new SemanticTarget(
                        SemanticTargetKind.ROUTE,
                        "POST /orders",
                        Optional.empty())));
    }

    private EvidenceRef evidence(String revision) {
        return new EvidenceRef(
                "java-semantic-service",
                new RepositoryId("order-service"),
                new RepositoryRevision(revision),
                new SemanticTarget(
                        SemanticTargetKind.SYMBOL,
                        "com.example.OrderController#create",
                        Optional.empty()),
                1.0,
                List.of(),
                new ArtifactRef("sha256:entry-point"));
    }
}
