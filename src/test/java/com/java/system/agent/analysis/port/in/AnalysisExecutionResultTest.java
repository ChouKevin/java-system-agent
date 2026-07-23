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
import com.java.system.agent.analysis.domain.RepositoryDiscoverySource;
import com.java.system.agent.analysis.domain.RepositoryId;
import com.java.system.agent.analysis.domain.RepositoryRevision;
import com.java.system.agent.analysis.domain.RepositoryScope;
import com.java.system.agent.analysis.domain.RepositorySelection;
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
    void acceptsAlignedTerminalResults() {
        for (TerminalCase terminalCase : List.of(
                new TerminalCase(
                        AnalysisOutcome.COMPLETED,
                        AttemptOutcome.COMPLETED,
                        AnalysisStatus.COMPLETED,
                        AnalysisTerminationReason.GOAL_COMPLETED),
                new TerminalCase(
                        AnalysisOutcome.INCONCLUSIVE,
                        AttemptOutcome.INCONCLUSIVE,
                        AnalysisStatus.INCONCLUSIVE,
                        AnalysisTerminationReason.BUDGET_EXHAUSTED),
                new TerminalCase(
                        AnalysisOutcome.FAILED,
                        AttemptOutcome.FAILED,
                        AnalysisStatus.FAILED,
                        AnalysisTerminationReason.RUNTIME_FAILURE),
                new TerminalCase(
                        AnalysisOutcome.CANCELLED,
                        AttemptOutcome.CANCELLED,
                        AnalysisStatus.CANCELLED,
                        AnalysisTerminationReason.CANCELLED),
                new TerminalCase(
                        AnalysisOutcome.INCONCLUSIVE,
                        AttemptOutcome.STALE,
                        AnalysisStatus.STALE,
                        AnalysisTerminationReason.REVISION_RESTART_LIMIT))) {
            TerminalData terminalData = terminalData(
                    terminalCase.runOutcome(),
                    terminalCase.attemptOutcome(),
                    terminalCase.status());

            AnalysisExecutionResult result = new AnalysisExecutionResult(
                    terminalData.run(), terminalData.finalState(), terminalCase.reason());

            assertThat(result.finalState().status()).isEqualTo(terminalCase.status());
        }
    }

    @Test
    void rejectsAnUnconcludedRun() {
        AnalysisAttempt attempt = AnalysisAttempt.start(
                attemptId(), RevisionVector.empty(), budget());
        AnalysisRun run = AnalysisRun.start(runId(), attempt);

        assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisExecutionResult(
                run, state(runId(), attemptId(), AnalysisStatus.COMPLETED,
                RepositoryScope.of(List.of()), RevisionVector.empty(), budget()),
                AnalysisTerminationReason.GOAL_COMPLETED));
    }

    @Test
    void rejectsAConcludedRunWhoseCurrentAttemptHasNoOutcome() {
        AnalysisRun run = new AnalysisRun(
                runId(),
                List.of(AnalysisAttempt.start(attemptId(), RevisionVector.empty(), budget())),
                Optional.of(AnalysisOutcome.COMPLETED));

        assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisExecutionResult(
                run, state(runId(), attemptId(), AnalysisStatus.COMPLETED,
                RepositoryScope.of(List.of()), RevisionVector.empty(), budget()),
                AnalysisTerminationReason.GOAL_COMPLETED));
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
            TerminalData terminalData = terminalData(
                    AnalysisOutcome.COMPLETED, AttemptOutcome.COMPLETED, AnalysisStatus.COMPLETED);

            assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisExecutionResult(
                    terminalData.run(), state(
                    terminalData.run().id(),
                    terminalData.run().currentAttempt().id(),
                    nonTerminalStatus,
                    terminalData.scope(),
                    terminalData.revisionVector(),
                    terminalData.budget()),
                    AnalysisTerminationReason.GOAL_COMPLETED));
        }
    }

    @Test
    void rejectsNullFields() {
        TerminalData terminalData = terminalData(
                AnalysisOutcome.COMPLETED, AttemptOutcome.COMPLETED, AnalysisStatus.COMPLETED);

        assertThatNullPointerException().isThrownBy(() -> new AnalysisExecutionResult(
                null, terminalData.finalState(), AnalysisTerminationReason.GOAL_COMPLETED));
        assertThatNullPointerException().isThrownBy(() -> new AnalysisExecutionResult(
                terminalData.run(), null, AnalysisTerminationReason.GOAL_COMPLETED));
        assertThatNullPointerException().isThrownBy(() -> new AnalysisExecutionResult(
                terminalData.run(), terminalData.finalState(), null));
    }

    @Test
    void rejectsAMismatchedRunId() {
        TerminalData terminalData = terminalData(
                AnalysisOutcome.COMPLETED, AttemptOutcome.COMPLETED, AnalysisStatus.COMPLETED);

        assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisExecutionResult(
                terminalData.run(), state(
                new AnalysisRunId("other-run"),
                terminalData.run().currentAttempt().id(),
                AnalysisStatus.COMPLETED,
                terminalData.scope(),
                terminalData.revisionVector(),
                terminalData.budget()),
                AnalysisTerminationReason.GOAL_COMPLETED));
    }

    @Test
    void rejectsAMismatchedAttemptId() {
        TerminalData terminalData = terminalData(
                AnalysisOutcome.COMPLETED, AttemptOutcome.COMPLETED, AnalysisStatus.COMPLETED);

        assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisExecutionResult(
                terminalData.run(), state(
                terminalData.run().id(),
                new AnalysisAttemptId("other-attempt"),
                AnalysisStatus.COMPLETED,
                terminalData.scope(),
                terminalData.revisionVector(),
                terminalData.budget()),
                AnalysisTerminationReason.GOAL_COMPLETED));
    }

    @Test
    void rejectsAMismatchedFinalRevisionVector() {
        TerminalData terminalData = terminalData(
                AnalysisOutcome.COMPLETED, AttemptOutcome.COMPLETED, AnalysisStatus.COMPLETED);

        assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisExecutionResult(
                terminalData.run(), state(
                terminalData.run().id(),
                terminalData.run().currentAttempt().id(),
                AnalysisStatus.COMPLETED,
                terminalData.scope(),
                revisionVector(terminalData.scope(), "revision-2"),
                terminalData.budget()),
                AnalysisTerminationReason.GOAL_COMPLETED));
    }

    @Test
    void rejectsAMismatchedFinalBudget() {
        TerminalData terminalData = terminalData(
                AnalysisOutcome.COMPLETED, AttemptOutcome.COMPLETED, AnalysisStatus.COMPLETED);

        assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisExecutionResult(
                terminalData.run(), state(
                terminalData.run().id(),
                terminalData.run().currentAttempt().id(),
                AnalysisStatus.COMPLETED,
                terminalData.scope(),
                terminalData.revisionVector(),
                new AnalysisBudget(10, 1, 5, 0)),
                AnalysisTerminationReason.GOAL_COMPLETED));
    }

    @Test
    void rejectsCurrentAttemptOutcomeThatDoesNotMatchTheFinalStateStatus() {
        TerminalData terminalData = terminalData(
                AnalysisOutcome.FAILED, AttemptOutcome.FAILED, AnalysisStatus.COMPLETED);

        assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisExecutionResult(
                terminalData.run(), terminalData.finalState(), AnalysisTerminationReason.RUNTIME_FAILURE));
    }

    @Test
    void rejectsRunOutcomeThatDoesNotMatchTheFinalStateStatus() {
        TerminalData terminalData = terminalData(
                AnalysisOutcome.FAILED, AttemptOutcome.COMPLETED, AnalysisStatus.COMPLETED);

        assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisExecutionResult(
                terminalData.run(), terminalData.finalState(), AnalysisTerminationReason.RUNTIME_FAILURE));
    }

    @Test
    void rejectsGoalCompletedReasonThatContradictsTheTerminalResult() {
        TerminalData terminalData = terminalData(
                AnalysisOutcome.FAILED, AttemptOutcome.FAILED, AnalysisStatus.FAILED);

        assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisExecutionResult(
                terminalData.run(), terminalData.finalState(), AnalysisTerminationReason.GOAL_COMPLETED));
    }

    @Test
    void rejectsCancelledReasonThatContradictsTheTerminalResult() {
        TerminalData terminalData = terminalData(
                AnalysisOutcome.COMPLETED, AttemptOutcome.COMPLETED, AnalysisStatus.COMPLETED);

        assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisExecutionResult(
                terminalData.run(), terminalData.finalState(), AnalysisTerminationReason.CANCELLED));
    }

    private TerminalData terminalData(
            AnalysisOutcome runOutcome,
            AttemptOutcome attemptOutcome,
            AnalysisStatus status) {
        AnalysisRunId runId = runId();
        AnalysisAttemptId attemptId = attemptId();
        RepositoryScope scope = scope();
        RevisionVector revisionVector = revisionVector(scope, "revision-1");
        AnalysisBudget budget = budget();
        AnalysisRun run = AnalysisRun.start(
                runId,
                AnalysisAttempt.start(attemptId, revisionVector, budget).conclude(attemptOutcome))
                .conclude(runOutcome);
        AnalysisState finalState = state(runId, attemptId, status, scope, revisionVector, budget);
        return new TerminalData(run, finalState, scope, revisionVector, budget);
    }

    private AnalysisState state(
            AnalysisRunId runId,
            AnalysisAttemptId attemptId,
            AnalysisStatus status,
            RepositoryScope scope,
            RevisionVector revisionVector,
            AnalysisBudget budget) {
        return new AnalysisState(
                runId,
                attemptId,
                1,
                status,
                scope,
                revisionVector,
                Collections.emptySortedMap(),
                Set.of(),
                List.of(),
                List.of(),
                budget);
    }

    private AnalysisRunId runId() {
        return new AnalysisRunId("run-1");
    }

    private AnalysisAttemptId attemptId() {
        return new AnalysisAttemptId("attempt-1");
    }

    private RepositoryScope scope() {
        return RepositoryScope.of(List.of(new RepositorySelection(
                new RepositoryId("order-service"),
                "Selected for the result consistency test",
                true,
                RepositoryDiscoverySource.USER)));
    }

    private RevisionVector revisionVector(RepositoryScope scope, String revision) {
        RepositoryId repositoryId = new RepositoryId("order-service");
        return RevisionVector.empty().pin(
                scope, repositoryId, new RepositoryRevision(revision));
    }

    private AnalysisBudget budget() {
        return AnalysisBudget.of(10, 5);
    }

    private record TerminalCase(
            AnalysisOutcome runOutcome,
            AttemptOutcome attemptOutcome,
            AnalysisStatus status,
            AnalysisTerminationReason reason) {
    }

    private record TerminalData(
            AnalysisRun run,
            AnalysisState finalState,
            RepositoryScope scope,
            RevisionVector revisionVector,
            AnalysisBudget budget) {
    }
}
