package com.java.system.agent.analysis.port.in;

import com.java.system.agent.analysis.domain.AnalysisAttempt;
import com.java.system.agent.analysis.domain.AnalysisAttemptId;
import com.java.system.agent.analysis.domain.AnalysisBudget;
import com.java.system.agent.analysis.domain.AnalysisOutcome;
import com.java.system.agent.analysis.domain.AnalysisRun;
import com.java.system.agent.analysis.domain.AnalysisRunId;
import com.java.system.agent.analysis.domain.AnalysisState;
import com.java.system.agent.analysis.domain.AnalysisStatus;
import com.java.system.agent.analysis.domain.AttemptOutcome;
import com.java.system.agent.analysis.domain.RepositoryScope;
import com.java.system.agent.analysis.domain.RevisionVector;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class AnalysisExecutionResultTest {

    @Test
    void acceptsConcludedRunsWithEveryTerminalState() {
        for (AnalysisStatus terminalStatus : List.of(
                AnalysisStatus.STALE,
                AnalysisStatus.COMPLETED,
                AnalysisStatus.INCONCLUSIVE,
                AnalysisStatus.FAILED,
                AnalysisStatus.CANCELLED)) {
            AnalysisExecutionResult result = new AnalysisExecutionResult(
                    concludedRun(), state(terminalStatus), AnalysisTerminationReason.GOAL_COMPLETED);

            assertThat(result.finalState().status()).isEqualTo(terminalStatus);
        }
    }

    @Test
    void rejectsAnUnconcludedRun() {
        AnalysisAttempt attempt = AnalysisAttempt.start(
                new AnalysisAttemptId("attempt-1"), RevisionVector.empty(), AnalysisBudget.of(10, 5));
        AnalysisRun run = AnalysisRun.start(new AnalysisRunId("run-1"), attempt);

        assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisExecutionResult(
                run, state(AnalysisStatus.COMPLETED), AnalysisTerminationReason.GOAL_COMPLETED));
    }

    @Test
    void rejectsANonterminalFinalState() {
        for (AnalysisStatus nonTerminalStatus : List.of(
                AnalysisStatus.RECEIVED,
                AnalysisStatus.UNDERSTANDING,
                AnalysisStatus.SCOPE_RESOLVING,
                AnalysisStatus.REVISION_PINNING,
                AnalysisStatus.PLANNING,
                AnalysisStatus.EXECUTING,
                AnalysisStatus.COMPOSING,
                AnalysisStatus.VERIFYING)) {
            assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisExecutionResult(
                    concludedRun(), state(nonTerminalStatus), AnalysisTerminationReason.GOAL_COMPLETED));
        }
    }

    @Test
    void rejectsNullFields() {
        assertThatNullPointerException().isThrownBy(() -> new AnalysisExecutionResult(
                null, state(AnalysisStatus.COMPLETED), AnalysisTerminationReason.GOAL_COMPLETED));
        assertThatNullPointerException().isThrownBy(() -> new AnalysisExecutionResult(
                concludedRun(), null, AnalysisTerminationReason.GOAL_COMPLETED));
        assertThatNullPointerException().isThrownBy(() -> new AnalysisExecutionResult(
                concludedRun(), state(AnalysisStatus.COMPLETED), null));
    }

    private AnalysisRun concludedRun() {
        AnalysisAttempt concludedAttempt = AnalysisAttempt.start(
                new AnalysisAttemptId("attempt-1"), RevisionVector.empty(), AnalysisBudget.of(10, 5))
                .conclude(AttemptOutcome.COMPLETED);
        AnalysisRun activeRun = new AnalysisRun(
                new AnalysisRunId("run-1"), List.of(concludedAttempt), Optional.empty());
        return activeRun.conclude(AnalysisOutcome.COMPLETED);
    }

    private AnalysisState state(AnalysisStatus status) {
        return new AnalysisState(
                new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"),
                1,
                status,
                RepositoryScope.of(List.of()),
                RevisionVector.empty(),
                Collections.emptySortedMap(),
                Set.of(),
                List.of(),
                List.of(),
                AnalysisBudget.of(10, 5));
    }
}
