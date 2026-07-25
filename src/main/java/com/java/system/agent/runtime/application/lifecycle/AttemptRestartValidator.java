package com.java.system.agent.runtime.application.lifecycle;

import com.java.system.agent.runtime.domain.need.InformationNeed;
import com.java.system.agent.runtime.domain.need.InformationNeedId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.port.in.AnalysisExecutionCommand;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * revision mismatch 後重啟指令是否可套用到既有 lifecycle 的檢查
 *
 * <p>由 {@link AttemptLifecycleManager} 在
 * {@link AttemptLifecycleManager#restartAfterRevisionMismatch} 進入實際重啟流程前呼叫，
 * 確保重啟指令與原 attempt 描述的是同一件事——budget 上限比對只看
 * {@code maxSteps}／{@code maxSemanticCalls}，不比對用量，因為重啟本來就會帶著不同的用量</p>
 */
final class AttemptRestartValidator {

    private AttemptRestartValidator() {
    }

    public static void validate(AttemptLifecycle lifecycle, AnalysisExecutionCommand command) {
        if (!lifecycle.run().id().equals(command.runId())) {
            throw new IllegalArgumentException("analysis execution command belongs to another run");
        }
        if (!lifecycle.run().attempts().getFirst().id().equals(command.firstAttemptId())) {
            throw new IllegalArgumentException(
                    "analysis execution command belongs to another first attempt");
        }
        validateRestartBudget(lifecycle, command);
        validateRestartInformationNeeds(lifecycle, command);
    }

    private static void validateRestartBudget(AttemptLifecycle lifecycle, AnalysisExecutionCommand command) {
        AttemptBudget commandBudget = command.attemptBudget();
        if (!hasSameBudgetLimits(commandBudget, lifecycle.run().currentAttempt().budget())
                || !hasSameBudgetLimits(commandBudget, lifecycle.state().budget())) {
            throw new IllegalArgumentException(
                    "analysis execution command has different attempt budget limits");
        }
    }

    private static boolean hasSameBudgetLimits(AttemptBudget firstBudget, AttemptBudget secondBudget) {
        return firstBudget.maxSteps() == secondBudget.maxSteps()
                && firstBudget.maxSemanticCalls() == secondBudget.maxSemanticCalls();
    }

    private static void validateRestartInformationNeeds(
            AttemptLifecycle lifecycle,
            AnalysisExecutionCommand command) {
        Set<InformationNeedId> registeredNeedIds = new TreeSet<>(
                lifecycle.state().pendingNeeds().keySet());
        registeredNeedIds.addAll(lifecycle.state().resolvedNeedIds());
        Map<InformationNeedId, InformationNeed> commandNeedsById = command.informationNeeds().stream()
                .collect(Collectors.toUnmodifiableMap(InformationNeed::id, Function.identity()));
        if (!registeredNeedIds.equals(commandNeedsById.keySet())) {
            throw new IllegalArgumentException(
                    "analysis execution command has different information need IDs");
        }

        // AttemptState retains values only for pending needs; resolved need IDs are guarded above.
        for (Map.Entry<InformationNeedId, InformationNeed> registeredPendingNeed
                : lifecycle.state().pendingNeeds().entrySet()) {
            InformationNeed commandNeed = commandNeedsById.get(registeredPendingNeed.getKey());
            if (!registeredPendingNeed.getValue().equals(commandNeed)) {
                throw new IllegalArgumentException(
                        "analysis execution command has a different pending information need");
            }
        }
    }
}
