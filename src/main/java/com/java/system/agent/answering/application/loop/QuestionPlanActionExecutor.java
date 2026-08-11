package com.java.system.agent.answering.application.loop;

import com.java.system.agent.answering.domain.action.PlanAction;
import com.java.system.agent.answering.domain.run.AgentEvent;
import com.java.system.agent.answering.domain.run.AgentRunState;

import java.util.Objects;

/**
 * 將已選定的問題解析計畫原子地保存為 run 轉換
 */
final class QuestionPlanActionExecutor {

    private final AgentRunTransitions transitions;

    QuestionPlanActionExecutor(AgentRunTransitions transitions) {
        this.transitions = Objects.requireNonNull(transitions, "agent run transitions must not be null");
    }

    AgentRunState execute(AgentRunState state, PlanAction action) {
        Objects.requireNonNull(state, "agent run state must not be null");
        Objects.requireNonNull(action, "plan action must not be null");
        return transitions.apply(state, new AgentEvent.QuestionPlanCreated(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), action.plan()));
    }
}
