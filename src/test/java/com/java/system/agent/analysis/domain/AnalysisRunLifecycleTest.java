package com.java.system.agent.analysis.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AnalysisRunLifecycleTest {

    @Test
    void concludesCurrentAttemptWithFinalRevisionVectorBudgetAndOutcome() {
        AnalysisAttempt initialAttempt = AnalysisAttempt.start(
                new AnalysisAttemptId("attempt-1"),
                RevisionVector.empty(),
                AnalysisBudget.of(10, 5));
        AnalysisRun run = AnalysisRun.start(new AnalysisRunId("run-1"), initialAttempt);
        RepositoryId repositoryId = new RepositoryId("order-service");
        RepositoryScope repositoryScope = RepositoryScope.of(List.of(new RepositorySelection(
                repositoryId,
                "Lifecycle test scope",
                true,
                RepositoryDiscoverySource.USER)));
        RevisionVector finalRevisionVector = RevisionVector.empty().pin(
                repositoryScope,
                repositoryId,
                new RepositoryRevision("order-123"));
        AnalysisBudget finalBudget = new AnalysisBudget(10, 3, 5, 2);

        AnalysisRun concludedAttemptRun = run.concludeCurrentAttempt(
                finalRevisionVector,
                finalBudget,
                AttemptOutcome.COMPLETED);

        assertThat(concludedAttemptRun.id()).isEqualTo(run.id());
        assertThat(concludedAttemptRun.outcome()).isEmpty();
        assertThat(concludedAttemptRun.currentAttempt())
                .extracting(
                        AnalysisAttempt::id,
                        AnalysisAttempt::revisionVector,
                        AnalysisAttempt::budget,
                        AnalysisAttempt::outcome)
                .containsExactly(
                        initialAttempt.id(),
                        finalRevisionVector,
                        finalBudget,
                        Optional.of(AttemptOutcome.COMPLETED));
        assertThat(run.currentAttempt()).isEqualTo(initialAttempt);
    }

    @Test
    void rejectsSecondCurrentAttemptConclusion() {
        AnalysisRun run = AnalysisRun.start(
                new AnalysisRunId("run-1"),
                AnalysisAttempt.start(
                        new AnalysisAttemptId("attempt-1"),
                        RevisionVector.empty(),
                        AnalysisBudget.of(10, 5)));
        AnalysisRun concludedAttemptRun = run.concludeCurrentAttempt(
                RevisionVector.empty(),
                AnalysisBudget.of(10, 5),
                AttemptOutcome.INCONCLUSIVE);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> concludedAttemptRun.concludeCurrentAttempt(
                        RevisionVector.empty(),
                        AnalysisBudget.of(10, 5),
                        AttemptOutcome.INCONCLUSIVE))
                .withMessageContaining("concluded");
    }
}
