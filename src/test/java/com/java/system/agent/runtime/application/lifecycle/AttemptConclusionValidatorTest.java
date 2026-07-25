package com.java.system.agent.runtime.application.lifecycle;

import com.java.system.agent.runtime.domain.run.AnalysisAttempt;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRun;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.run.AttemptOutcome;
import com.java.system.agent.runtime.domain.run.AttemptState;
import com.java.system.agent.runtime.domain.run.AttemptStatus;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.scope.RepositoryScope;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AttemptConclusionValidatorTest {

    private static final AnalysisRunId RUN_ID = new AnalysisRunId("run-1");
    private static final AnalysisAttemptId ATTEMPT_ID = new AnalysisAttemptId("attempt-1");
    private static final AttemptBudget BUDGET = AttemptBudget.of(10, 5);

    @Test
    @DisplayName("an inactive lifecycle cannot be concluded")
    void rejectsAnInactiveLifecycle() {
        AttemptLifecycle inactiveLifecycle = lifecycle(AttemptStatus.STALE);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> AttemptConclusionValidator.validateActive(inactiveLifecycle));

        assertEquals(
                "analysis operation requires an active lifecycle before applying an active operation",
                exception.getMessage());
    }

    @Test
    @DisplayName("an active lifecycle is accepted")
    void acceptsAnActiveLifecycle() {
        AttemptLifecycle activeLifecycle = lifecycle(AttemptStatus.EXECUTING);

        assertDoesNotThrow(() -> AttemptConclusionValidator.validateActive(activeLifecycle));
    }

    @Test
    @DisplayName("an incompatible attempt/run outcome pair is rejected")
    void rejectsAnIncompatibleOutcomePair() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> AttemptConclusionValidator.validateCompatibleOutcomes(
                        AttemptOutcome.FAILED, RunOutcome.COMPLETED));

        assertEquals("analysis attempt and run outcomes are not compatible", exception.getMessage());
    }

    @Test
    @DisplayName("a compatible attempt/run outcome pair is accepted")
    void acceptsACompatibleOutcomePair() {
        assertDoesNotThrow(() -> AttemptConclusionValidator.validateCompatibleOutcomes(
                AttemptOutcome.COMPLETED, RunOutcome.COMPLETED));
    }

    private AttemptLifecycle lifecycle(AttemptStatus status) {
        AnalysisAttempt attempt = AnalysisAttempt.start(ATTEMPT_ID, RevisionVector.empty(), BUDGET);
        AnalysisRun run = AnalysisRun.start(RUN_ID, attempt);
        AttemptState state = new AttemptState(
                RUN_ID,
                ATTEMPT_ID,
                0,
                status,
                RepositoryScope.of(List.of()),
                RevisionVector.empty(),
                Collections.emptySortedMap(),
                Set.of(),
                List.of(),
                List.of(),
                BUDGET);
        return new AttemptLifecycle(run, state, 0);
    }
}
