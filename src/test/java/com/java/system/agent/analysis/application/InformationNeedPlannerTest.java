package com.java.system.agent.analysis.application;

import com.java.system.agent.analysis.domain.AnalysisAttemptId;
import com.java.system.agent.analysis.domain.AnalysisBudget;
import com.java.system.agent.analysis.domain.AnalysisRunId;
import com.java.system.agent.analysis.domain.AnalysisState;
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
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InformationNeedPlannerTest {

    private final InformationNeedPlanner planner = new InformationNeedPlanner();

    @Test
    void deterministicallyPlansUniqueCapabilityWithoutChangingState() {
        AnalysisState state = pinnedState(AnalysisBudget.of(5, 2));
        SemanticCapability capability = capability("entry-point", "v1");
        SemanticCapabilityRegistry registry = new SemanticCapabilityRegistry(List.of(capability));

        PlanningResult result = planner.plan(state, need(), registry);

        assertThat(result.status()).isEqualTo(PlanningStatus.PLANNED);
        assertThat(result.plannedCapability()).isPresent().get()
                .satisfies(planned -> {
                    assertThat(planned.capability()).isEqualTo(capability);
                    assertThat(planned.repositoryId()).isEqualTo(new RepositoryId("order-service"));
                    assertThat(planned.expectedRevision()).isEqualTo(new RepositoryRevision("ord-456"));
                });
        assertThat(state.stateRevision()).isEqualTo(2);
        assertThat(state.budget().usedSemanticCalls()).isZero();
    }

    @Test
    void diagnosesMissingAndAmbiguousCapabilities() {
        AnalysisState state = pinnedState(AnalysisBudget.of(5, 2));

        PlanningResult missing = planner.plan(
                state, need(), new SemanticCapabilityRegistry(List.of()));
        PlanningResult ambiguous = planner.plan(
                state,
                need(),
                new SemanticCapabilityRegistry(List.of(
                        capability("entry-point", "v1"),
                        capability("entry-point-alternative", "v1"))));

        assertThat(missing.status()).isEqualTo(PlanningStatus.CAPABILITY_MISSING);
        assertThat(ambiguous.status()).isEqualTo(PlanningStatus.AMBIGUOUS_CAPABILITY);
        assertThat(ambiguous.diagnosis()).contains("entry-point/v1", "entry-point-alternative/v1");
    }

    @Test
    void diagnosesMissingTargetAndRevisionPrerequisites() {
        AnalysisState received = AnalysisState.initial(
                new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"),
                AnalysisBudget.of(5, 2));
        InformationNeed withoutTarget = new InformationNeed(
                new InformationNeedId("need-entry-point"),
                InformationNeedType.ENTRY_POINT,
                "Locate the order entry point",
                true,
                List.of(new RepositoryId("order-service")),
                List.of());
        SemanticCapabilityRegistry registry = new SemanticCapabilityRegistry(
                List.of(capability("entry-point", "v1")));

        PlanningResult missingScope = planner.plan(received, need(), registry);
        PlanningResult missingTarget = planner.plan(pinnedState(AnalysisBudget.of(5, 2)), withoutTarget, registry);

        assertThat(missingScope.status()).isEqualTo(PlanningStatus.PREREQUISITE_MISSING);
        assertThat(missingTarget.status()).isEqualTo(PlanningStatus.PREREQUISITE_MISSING);
    }

    @Test
    void stopsWhenSemanticCallBudgetIsExhausted() {
        AnalysisBudget exhausted = AnalysisBudget.of(5, 1).consumeSemanticCall();
        AnalysisState state = pinnedState(exhausted);
        SemanticCapabilityRegistry registry = new SemanticCapabilityRegistry(
                List.of(capability("entry-point", "v1")));

        PlanningResult result = planner.plan(state, need(), registry);

        assertThat(result.status()).isEqualTo(PlanningStatus.BUDGET_EXHAUSTED);
        assertThat(result.plannedCapability()).isEmpty();
    }

    private AnalysisState pinnedState(AnalysisBudget budget) {
        StateReducer reducer = new DefaultStateReducer();
        AnalysisRunId runId = new AnalysisRunId("run-1");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        AnalysisState state = AnalysisState.initial(runId, attemptId, budget);
        RepositoryScope scope = RepositoryScope.of(List.of(new RepositorySelection(
                new RepositoryId("order-service"),
                "Selected for planner test",
                true,
                RepositoryDiscoverySource.USER)));
        state = reducer.reduce(
                state,
                new AnalysisEvent.ScopeResolved(runId, attemptId, 0, scope)).candidateState();
        return reducer.reduce(
                state,
                new AnalysisEvent.RevisionPinned(
                        runId,
                        attemptId,
                        1,
                        new RepositoryId("order-service"),
                        new RepositoryRevision("ord-456"))).candidateState();
    }

    private InformationNeed need() {
        SemanticTarget target = new SemanticTarget(
                SemanticTargetKind.ROUTE,
                "POST /orders",
                Optional.empty());
        return new InformationNeed(
                new InformationNeedId("need-entry-point"),
                InformationNeedType.ENTRY_POINT,
                "Locate the order entry point",
                true,
                List.of(new RepositoryId("order-service")),
                List.of(target));
    }

    private SemanticCapability capability(String name, String version) {
        return new SemanticCapability(
                name,
                version,
                Set.of(InformationNeedType.ENTRY_POINT),
                "semantic-query/v1",
                "semantic-result/v1",
                true,
                true,
                1,
                Duration.ofSeconds(5),
                1);
    }
}
