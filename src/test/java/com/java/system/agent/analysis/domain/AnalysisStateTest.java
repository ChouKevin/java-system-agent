package com.java.system.agent.analysis.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AnalysisStateTest {

    @Test
    void initializesImmutableAuthoritativeStateAtRevisionZero() {
        AnalysisState state = AnalysisState.initial(
                new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"),
                AnalysisBudget.of(10, 5));

        assertThat(state.stateRevision()).isZero();
        assertThat(state.status()).isEqualTo(AnalysisStatus.RECEIVED);
        assertThat(state.repositoryScope().repositoryIds()).isEmpty();
        assertThat(state.revisionVector().repositoryIds()).isEmpty();
        assertThat(state.pendingNeeds()).isEmpty();
        assertThat(state.resolvedNeedIds()).isEmpty();
        assertThat(state.evidenceBindings()).isEmpty();
        assertThat(state.warnings()).isEmpty();
    }

    @Test
    void rejectsNegativeStateRevision() {
        assertThatIllegalArgumentException().isThrownBy(() -> AnalysisState.initial(
                        new AnalysisRunId("run-1"),
                        new AnalysisAttemptId("attempt-1"),
                        AnalysisBudget.of(10, 5))
                .withStateRevision(-1));
    }

    @Test
    void tracksStepAndSemanticCallBudgetsWithoutProviderPricing() {
        AnalysisBudget initial = AnalysisBudget.of(2, 1);

        AnalysisBudget consumed = initial.consumeStep().consumeSemanticCall();

        assertThat(initial.usedSteps()).isZero();
        assertThat(consumed.usedSteps()).isEqualTo(1);
        assertThat(consumed.usedSemanticCalls()).isEqualTo(1);
        assertThat(consumed.hasStepRemaining()).isTrue();
        assertThat(consumed.hasSemanticCallRemaining()).isFalse();
        assertThatIllegalArgumentException().isThrownBy(consumed::consumeSemanticCall);
    }
}
