package com.java.system.agent.analysis.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AnalysisRunTest {

    @Test
    void replacesStaleAttemptWithoutFailingTheRun() {
        AnalysisAttempt first = AnalysisAttempt.start(
                new AnalysisAttemptId("attempt-1"),
                RevisionVector.empty(),
                AnalysisBudget.of(10, 5));
        AnalysisRun run = AnalysisRun.start(new AnalysisRunId("run-1"), first);
        AnalysisAttempt stale = first.conclude(AttemptOutcome.STALE);
        AnalysisAttempt second = AnalysisAttempt.start(
                new AnalysisAttemptId("attempt-2"),
                RevisionVector.empty(),
                AnalysisBudget.of(10, 5));

        AnalysisRun retried = run.replaceCurrentAttempt(stale, second);

        assertThat(retried.attempts()).containsExactly(stale, second);
        assertThat(retried.currentAttempt()).isEqualTo(second);
        assertThat(retried.outcome()).isEmpty();
    }

    @Test
    void rejectsReplacementWhenCurrentAttemptIsNotStale() {
        AnalysisAttempt first = AnalysisAttempt.start(
                new AnalysisAttemptId("attempt-1"),
                RevisionVector.empty(),
                AnalysisBudget.of(10, 5));
        AnalysisRun run = AnalysisRun.start(new AnalysisRunId("run-1"), first);
        AnalysisAttempt completed = first.conclude(AttemptOutcome.COMPLETED);
        AnalysisAttempt second = AnalysisAttempt.start(
                new AnalysisAttemptId("attempt-2"),
                RevisionVector.empty(),
                AnalysisBudget.of(10, 5));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> run.replaceCurrentAttempt(completed, second))
                .withMessageContaining("STALE");
    }

    @Test
    void concludesRunSeparatelyFromAttemptOutcome() {
        AnalysisAttempt attempt = AnalysisAttempt.start(
                new AnalysisAttemptId("attempt-1"),
                RevisionVector.empty(),
                AnalysisBudget.of(10, 5)).conclude(AttemptOutcome.INCONCLUSIVE);
        AnalysisRun run = new AnalysisRun(
                new AnalysisRunId("run-1"),
                List.of(attempt),
                Optional.empty());

        AnalysisRun concluded = run.conclude(AnalysisOutcome.INCONCLUSIVE);

        assertThat(concluded.outcome()).contains(AnalysisOutcome.INCONCLUSIVE);
        assertThat(concluded.currentAttempt().outcome()).contains(AttemptOutcome.INCONCLUSIVE);
    }
}
