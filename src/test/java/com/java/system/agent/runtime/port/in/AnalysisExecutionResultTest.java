package com.java.system.agent.runtime.port.in;

import com.java.system.agent.runtime.domain.run.AnalysisAttempt;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.run.AnalysisRun;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptState;
import com.java.system.agent.runtime.domain.run.AttemptStatus;
import com.java.system.agent.runtime.domain.run.AttemptOutcome;
import com.java.system.agent.runtime.domain.scope.RepositoryDiscoverySource;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.domain.scope.RepositoryScope;
import com.java.system.agent.runtime.domain.scope.RepositorySelection;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
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
                        RunOutcome.COMPLETED,
                        AttemptOutcome.COMPLETED,
                        AttemptStatus.COMPLETED,
                        AnalysisTerminationReason.GOAL_COMPLETED),
                new TerminalCase(
                        RunOutcome.INCONCLUSIVE,
                        AttemptOutcome.INCONCLUSIVE,
                        AttemptStatus.INCONCLUSIVE,
                        AnalysisTerminationReason.CAPABILITY_MISSING),
                new TerminalCase(
                        RunOutcome.INCONCLUSIVE,
                        AttemptOutcome.INCONCLUSIVE,
                        AttemptStatus.INCONCLUSIVE,
                        AnalysisTerminationReason.PREREQUISITE_MISSING),
                new TerminalCase(
                        RunOutcome.INCONCLUSIVE,
                        AttemptOutcome.INCONCLUSIVE,
                        AttemptStatus.INCONCLUSIVE,
                        AnalysisTerminationReason.SEMANTIC_AMBIGUOUS),
                new TerminalCase(
                        RunOutcome.INCONCLUSIVE,
                        AttemptOutcome.INCONCLUSIVE,
                        AttemptStatus.INCONCLUSIVE,
                        AnalysisTerminationReason.SEMANTIC_FORBIDDEN),
                new TerminalCase(
                        RunOutcome.INCONCLUSIVE,
                        AttemptOutcome.INCONCLUSIVE,
                        AttemptStatus.INCONCLUSIVE,
                        AnalysisTerminationReason.SEMANTIC_UNAVAILABLE),
                new TerminalCase(
                        RunOutcome.FAILED,
                        AttemptOutcome.FAILED,
                        AttemptStatus.FAILED,
                        AnalysisTerminationReason.SEMANTIC_UNAVAILABLE),
                new TerminalCase(
                        RunOutcome.INCONCLUSIVE,
                        AttemptOutcome.INCONCLUSIVE,
                        AttemptStatus.INCONCLUSIVE,
                        AnalysisTerminationReason.BUDGET_EXHAUSTED),
                new TerminalCase(
                        RunOutcome.INCONCLUSIVE,
                        AttemptOutcome.INCONCLUSIVE,
                        AttemptStatus.INCONCLUSIVE,
                        AnalysisTerminationReason.NO_PROGRESS),
                new TerminalCase(
                        RunOutcome.FAILED,
                        AttemptOutcome.FAILED,
                        AttemptStatus.FAILED,
                        AnalysisTerminationReason.RUNTIME_FAILURE),
                new TerminalCase(
                        RunOutcome.CANCELLED,
                        AttemptOutcome.CANCELLED,
                        AttemptStatus.CANCELLED,
                        AnalysisTerminationReason.CANCELLED),
                new TerminalCase(
                        RunOutcome.INCONCLUSIVE,
                        AttemptOutcome.STALE,
                        AttemptStatus.STALE,
                        AnalysisTerminationReason.REVISION_RESTART_LIMIT),
                new TerminalCase(
                        RunOutcome.INCONCLUSIVE,
                        AttemptOutcome.INCONCLUSIVE,
                        AttemptStatus.INCONCLUSIVE,
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
    void rejectsReasonIncompatibleWithAnOtherwiseAlignedTerminalResult() {
        for (TerminalCase terminalCase : List.of(
                new TerminalCase(
                        RunOutcome.FAILED,
                        AttemptOutcome.FAILED,
                        AttemptStatus.FAILED,
                        AnalysisTerminationReason.GOAL_COMPLETED),
                new TerminalCase(
                        RunOutcome.COMPLETED,
                        AttemptOutcome.COMPLETED,
                        AttemptStatus.COMPLETED,
                        AnalysisTerminationReason.CAPABILITY_MISSING),
                new TerminalCase(
                        RunOutcome.COMPLETED,
                        AttemptOutcome.COMPLETED,
                        AttemptStatus.COMPLETED,
                        AnalysisTerminationReason.PREREQUISITE_MISSING),
                new TerminalCase(
                        RunOutcome.COMPLETED,
                        AttemptOutcome.COMPLETED,
                        AttemptStatus.COMPLETED,
                        AnalysisTerminationReason.SEMANTIC_AMBIGUOUS),
                new TerminalCase(
                        RunOutcome.COMPLETED,
                        AttemptOutcome.COMPLETED,
                        AttemptStatus.COMPLETED,
                        AnalysisTerminationReason.SEMANTIC_FORBIDDEN),
                new TerminalCase(
                        RunOutcome.COMPLETED,
                        AttemptOutcome.COMPLETED,
                        AttemptStatus.COMPLETED,
                        AnalysisTerminationReason.SEMANTIC_UNAVAILABLE),
                new TerminalCase(
                        RunOutcome.FAILED,
                        AttemptOutcome.FAILED,
                        AttemptStatus.FAILED,
                        AnalysisTerminationReason.BUDGET_EXHAUSTED),
                new TerminalCase(
                        RunOutcome.COMPLETED,
                        AttemptOutcome.COMPLETED,
                        AttemptStatus.COMPLETED,
                        AnalysisTerminationReason.NO_PROGRESS),
                new TerminalCase(
                        RunOutcome.FAILED,
                        AttemptOutcome.FAILED,
                        AttemptStatus.FAILED,
                        AnalysisTerminationReason.REVISION_RESTART_LIMIT),
                new TerminalCase(
                        RunOutcome.COMPLETED,
                        AttemptOutcome.COMPLETED,
                        AttemptStatus.COMPLETED,
                        AnalysisTerminationReason.CANCELLED),
                new TerminalCase(
                        RunOutcome.COMPLETED,
                        AttemptOutcome.COMPLETED,
                        AttemptStatus.COMPLETED,
                        AnalysisTerminationReason.RUNTIME_FAILURE))) {
            TerminalData terminalData = terminalData(
                    terminalCase.runOutcome(),
                    terminalCase.attemptOutcome(),
                    terminalCase.status());

            assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisExecutionResult(
                    terminalData.run(), terminalData.finalState(), terminalCase.reason()))
                    .withMessage("analysis termination reason must match the terminal result");
        }
    }

    @Test
    void rejectsAnUnconcludedRun() {
        AnalysisAttempt attempt = AnalysisAttempt.start(
                attemptId(), RevisionVector.empty(), budget());
        AnalysisRun run = AnalysisRun.start(runId(), attempt);

        assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisExecutionResult(
                run, state(runId(), attemptId(), AttemptStatus.COMPLETED,
                RepositoryScope.of(List.of()), RevisionVector.empty(), budget()),
                AnalysisTerminationReason.GOAL_COMPLETED));
    }

    @Test
    void rejectsAConcludedRunWhoseCurrentAttemptHasNoOutcome() {
        AnalysisRun run = new AnalysisRun(
                runId(),
                List.of(AnalysisAttempt.start(attemptId(), RevisionVector.empty(), budget())),
                Optional.of(RunOutcome.COMPLETED));

        assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisExecutionResult(
                run, state(runId(), attemptId(), AttemptStatus.COMPLETED,
                RepositoryScope.of(List.of()), RevisionVector.empty(), budget()),
                AnalysisTerminationReason.GOAL_COMPLETED));
    }

    @Test
    void rejectsANonterminalFinalState() {
        for (AttemptStatus nonTerminalStatus : List.of(
                AttemptStatus.RECEIVED,
                AttemptStatus.REVISION_PINNING,
                AttemptStatus.PLANNING,
                AttemptStatus.EXECUTING)) {
            TerminalData terminalData = terminalData(
                    RunOutcome.COMPLETED, AttemptOutcome.COMPLETED, AttemptStatus.COMPLETED);

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
                RunOutcome.COMPLETED, AttemptOutcome.COMPLETED, AttemptStatus.COMPLETED);

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
                RunOutcome.COMPLETED, AttemptOutcome.COMPLETED, AttemptStatus.COMPLETED);

        assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisExecutionResult(
                terminalData.run(), state(
                new AnalysisRunId("other-run"),
                terminalData.run().currentAttempt().id(),
                AttemptStatus.COMPLETED,
                terminalData.scope(),
                terminalData.revisionVector(),
                terminalData.budget()),
                AnalysisTerminationReason.GOAL_COMPLETED));
    }

    @Test
    void rejectsAMismatchedAttemptId() {
        TerminalData terminalData = terminalData(
                RunOutcome.COMPLETED, AttemptOutcome.COMPLETED, AttemptStatus.COMPLETED);

        assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisExecutionResult(
                terminalData.run(), state(
                terminalData.run().id(),
                new AnalysisAttemptId("other-attempt"),
                AttemptStatus.COMPLETED,
                terminalData.scope(),
                terminalData.revisionVector(),
                terminalData.budget()),
                AnalysisTerminationReason.GOAL_COMPLETED));
    }

    @Test
    void rejectsAMismatchedFinalRevisionVector() {
        TerminalData terminalData = terminalData(
                RunOutcome.COMPLETED, AttemptOutcome.COMPLETED, AttemptStatus.COMPLETED);

        assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisExecutionResult(
                terminalData.run(), state(
                terminalData.run().id(),
                terminalData.run().currentAttempt().id(),
                AttemptStatus.COMPLETED,
                terminalData.scope(),
                revisionVector(terminalData.scope(), "revision-2"),
                terminalData.budget()),
                AnalysisTerminationReason.GOAL_COMPLETED));
    }

    @Test
    void rejectsAMismatchedFinalBudget() {
        TerminalData terminalData = terminalData(
                RunOutcome.COMPLETED, AttemptOutcome.COMPLETED, AttemptStatus.COMPLETED);

        assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisExecutionResult(
                terminalData.run(), state(
                terminalData.run().id(),
                terminalData.run().currentAttempt().id(),
                AttemptStatus.COMPLETED,
                terminalData.scope(),
                terminalData.revisionVector(),
                new AttemptBudget(10, 1, 5, 0)),
                AnalysisTerminationReason.GOAL_COMPLETED));
    }

    @Test
    void rejectsCurrentAttemptOutcomeThatDoesNotMatchTheFinalStateStatus() {
        TerminalData terminalData = terminalData(
                RunOutcome.FAILED, AttemptOutcome.FAILED, AttemptStatus.COMPLETED);

        assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisExecutionResult(
                terminalData.run(), terminalData.finalState(), AnalysisTerminationReason.RUNTIME_FAILURE));
    }

    @Test
    void rejectsRunOutcomeThatDoesNotMatchTheFinalStateStatus() {
        TerminalData terminalData = terminalData(
                RunOutcome.FAILED, AttemptOutcome.COMPLETED, AttemptStatus.COMPLETED);

        assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisExecutionResult(
                terminalData.run(), terminalData.finalState(), AnalysisTerminationReason.RUNTIME_FAILURE));
    }

    @Test
    void rejectsGoalCompletedReasonThatContradictsTheTerminalResult() {
        TerminalData terminalData = terminalData(
                RunOutcome.FAILED, AttemptOutcome.FAILED, AttemptStatus.FAILED);

        assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisExecutionResult(
                terminalData.run(), terminalData.finalState(), AnalysisTerminationReason.GOAL_COMPLETED));
    }

    @Test
    void rejectsCancelledReasonThatContradictsTheTerminalResult() {
        TerminalData terminalData = terminalData(
                RunOutcome.COMPLETED, AttemptOutcome.COMPLETED, AttemptStatus.COMPLETED);

        assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisExecutionResult(
                terminalData.run(), terminalData.finalState(), AnalysisTerminationReason.CANCELLED));
    }

    private TerminalData terminalData(
            RunOutcome runOutcome,
            AttemptOutcome attemptOutcome,
            AttemptStatus status) {
        AnalysisRunId runId = runId();
        AnalysisAttemptId attemptId = attemptId();
        RepositoryScope scope = scope();
        RevisionVector attemptRevisionVector = revisionVector(scope, "revision-1");
        RevisionVector finalStateRevisionVector = revisionVector(scope, "revision-1");
        AttemptBudget budget = budget();
        AnalysisRun run = AnalysisRun.start(
                runId,
                AnalysisAttempt.start(attemptId, attemptRevisionVector, budget).conclude(attemptOutcome))
                .conclude(runOutcome);
        AttemptState finalState = state(
                runId, attemptId, status, scope, finalStateRevisionVector, budget);
        return new TerminalData(run, finalState, scope, finalStateRevisionVector, budget);
    }

    private AttemptState state(
            AnalysisRunId runId,
            AnalysisAttemptId attemptId,
            AttemptStatus status,
            RepositoryScope scope,
            RevisionVector revisionVector,
            AttemptBudget budget) {
        return new AttemptState(
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

    private AttemptBudget budget() {
        return AttemptBudget.of(10, 5);
    }

    private record TerminalCase(
            RunOutcome runOutcome,
            AttemptOutcome attemptOutcome,
            AttemptStatus status,
            AnalysisTerminationReason reason) {
    }

    private record TerminalData(
            AnalysisRun run,
            AttemptState finalState,
            RepositoryScope scope,
            RevisionVector revisionVector,
            AttemptBudget budget) {
    }
}
