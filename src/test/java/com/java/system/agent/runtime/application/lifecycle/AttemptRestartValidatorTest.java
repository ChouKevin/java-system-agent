package com.java.system.agent.runtime.application.lifecycle;

import com.java.system.agent.runtime.domain.need.Goal;
import com.java.system.agent.runtime.domain.need.InformationNeed;
import com.java.system.agent.runtime.domain.need.InformationNeedId;
import com.java.system.agent.runtime.domain.need.InformationNeedType;
import com.java.system.agent.runtime.domain.run.AnalysisAttempt;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRun;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.run.AttemptState;
import com.java.system.agent.runtime.domain.run.AttemptStatus;
import com.java.system.agent.runtime.domain.scope.RepositoryDiscoverySource;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryScope;
import com.java.system.agent.runtime.domain.scope.RepositorySelection;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import com.java.system.agent.runtime.port.in.AnalysisExecutionCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AttemptRestartValidatorTest {

    private static final AnalysisRunId RUN_ID = new AnalysisRunId("run-1");
    private static final AnalysisAttemptId FIRST_ATTEMPT_ID = new AnalysisAttemptId("attempt-1");
    private static final RepositoryId REPOSITORY_ID = new RepositoryId("order-service");
    private static final AttemptBudget BUDGET_LIMITS = AttemptBudget.of(10, 5);
    private static final InformationNeedId PENDING_NEED_ID = new InformationNeedId("need-1");
    private static final InformationNeedId RESOLVED_NEED_ID = new InformationNeedId("need-2");

    @Test
    @DisplayName("restart command from another run is rejected")
    void rejectsCommandFromAnotherRun() {
        AttemptLifecycle lifecycle = validLifecycle();
        AnalysisExecutionCommand command = validCommandBuilder()
                .runId(new AnalysisRunId("run-2"))
                .build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> AttemptRestartValidator.validate(lifecycle, command));

        assertEquals("analysis execution command belongs to another run", exception.getMessage());
    }

    @Test
    @DisplayName("restart command from another first attempt is rejected")
    void rejectsCommandFromAnotherFirstAttempt() {
        AttemptLifecycle lifecycle = validLifecycle();
        AnalysisExecutionCommand command = validCommandBuilder()
                .firstAttemptId(new AnalysisAttemptId("other-attempt"))
                .build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> AttemptRestartValidator.validate(lifecycle, command));

        assertEquals("analysis execution command belongs to another first attempt", exception.getMessage());
    }

    @Test
    @DisplayName("restart command with different attempt budget limits is rejected")
    void rejectsCommandWithDifferentBudgetLimits() {
        AttemptLifecycle lifecycle = validLifecycle();
        AnalysisExecutionCommand command = validCommandBuilder()
                .attemptBudget(AttemptBudget.of(11, 5))
                .build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> AttemptRestartValidator.validate(lifecycle, command));

        assertEquals(
                "analysis execution command has different attempt budget limits",
                exception.getMessage());
    }

    @Test
    @DisplayName("restart command with different information need IDs is rejected")
    void rejectsCommandWithDifferentInformationNeedIds() {
        AttemptLifecycle lifecycle = validLifecycle();
        AnalysisExecutionCommand command = validCommandBuilder()
                .informationNeeds(List.of(pendingNeed()))
                .goal(new Goal("restart validator test", Set.of(PENDING_NEED_ID)))
                .build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> AttemptRestartValidator.validate(lifecycle, command));

        assertEquals(
                "analysis execution command has different information need IDs",
                exception.getMessage());
    }

    @Test
    @DisplayName("restart command with a changed pending information need is rejected")
    void rejectsCommandWithAChangedPendingInformationNeed() {
        AttemptLifecycle lifecycle = validLifecycle();
        InformationNeed changedPendingNeed = new InformationNeed(
                PENDING_NEED_ID,
                InformationNeedType.METHOD_IMPLEMENTATION,
                "Resolve a different question",
                true,
                List.of(REPOSITORY_ID),
                List.of());
        AnalysisExecutionCommand command = validCommandBuilder()
                .informationNeeds(List.of(changedPendingNeed, resolvedNeed()))
                .build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> AttemptRestartValidator.validate(lifecycle, command));

        assertEquals(
                "analysis execution command has a different pending information need",
                exception.getMessage());
    }

    @Test
    @DisplayName("restart command matching the lifecycle on all five dimensions is accepted")
    void acceptsAMatchingRestartCommand() {
        AttemptLifecycle lifecycle = validLifecycle();
        AnalysisExecutionCommand command = validCommandBuilder().build();

        assertDoesNotThrow(() -> AttemptRestartValidator.validate(lifecycle, command));
    }

    /**
     * 建立一個合法的重啟情境：run 與 first attempt 相符、budget 上限相符（但用量刻意不同，
     * 驗證重啟允許不同的用量)、pending/resolved need 的聯集與 command 的 need ID 相符
     */
    private AttemptLifecycle validLifecycle() {
        AnalysisAttempt firstAttempt = AnalysisAttempt.start(
                FIRST_ATTEMPT_ID, RevisionVector.empty(), BUDGET_LIMITS);
        AnalysisRun run = AnalysisRun.start(RUN_ID, firstAttempt);
        SortedMap<InformationNeedId, InformationNeed> pendingNeeds = new TreeMap<>();
        pendingNeeds.put(PENDING_NEED_ID, pendingNeed());
        AttemptState state = new AttemptState(
                RUN_ID,
                FIRST_ATTEMPT_ID,
                0,
                AttemptStatus.EXECUTING,
                RepositoryScope.of(List.of(new RepositorySelection(
                        REPOSITORY_ID,
                        "restart validator test scope",
                        true,
                        RepositoryDiscoverySource.USER))),
                RevisionVector.empty(),
                pendingNeeds,
                Set.of(RESOLVED_NEED_ID),
                List.of(),
                List.of(),
                new AttemptBudget(10, 3, 5, 1));
        return new AttemptLifecycle(run, state, 0);
    }

    private InformationNeed pendingNeed() {
        return new InformationNeed(
                PENDING_NEED_ID,
                InformationNeedType.METHOD_IMPLEMENTATION,
                "Resolve need-1",
                true,
                List.of(REPOSITORY_ID),
                List.of());
    }

    private InformationNeed resolvedNeed() {
        return new InformationNeed(
                RESOLVED_NEED_ID,
                InformationNeedType.METHOD_IMPLEMENTATION,
                "Resolve need-2",
                true,
                List.of(REPOSITORY_ID),
                List.of());
    }

    private CommandBuilder validCommandBuilder() {
        return new CommandBuilder()
                .runId(RUN_ID)
                .firstAttemptId(FIRST_ATTEMPT_ID)
                .informationNeeds(List.of(pendingNeed(), resolvedNeed()))
                .goal(new Goal("restart validator test", Set.of(PENDING_NEED_ID, RESOLVED_NEED_ID)))
                .attemptBudget(AttemptBudget.of(10, 5));
    }

    private static final class CommandBuilder {

        private AnalysisRunId runId;
        private AnalysisAttemptId firstAttemptId;
        private List<InformationNeed> informationNeeds;
        private Goal goal;
        private AttemptBudget attemptBudget;

        private CommandBuilder runId(AnalysisRunId value) {
            this.runId = value;
            return this;
        }

        private CommandBuilder firstAttemptId(AnalysisAttemptId value) {
            this.firstAttemptId = value;
            return this;
        }

        private CommandBuilder informationNeeds(List<InformationNeed> value) {
            this.informationNeeds = value;
            return this;
        }

        private CommandBuilder goal(Goal value) {
            this.goal = value;
            return this;
        }

        private CommandBuilder attemptBudget(AttemptBudget value) {
            this.attemptBudget = value;
            return this;
        }

        private AnalysisExecutionCommand build() {
            return new AnalysisExecutionCommand(
                    runId,
                    firstAttemptId,
                    RepositoryScope.of(List.of(new RepositorySelection(
                            REPOSITORY_ID,
                            "restart validator test scope",
                            true,
                            RepositoryDiscoverySource.USER))),
                    informationNeeds,
                    goal,
                    attemptBudget);
        }
    }
}
