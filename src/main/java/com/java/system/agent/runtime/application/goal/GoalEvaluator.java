package com.java.system.agent.runtime.application.goal;

import com.java.system.agent.runtime.domain.run.AttemptState;
import com.java.system.agent.runtime.domain.need.Goal;

import java.util.Optional;

/**
 * 判斷 attempt 是否已達成目標、應該終止的抽象
 *
 * <p>唯一實作是 {@link DefaultGoalEvaluator}；由
 * {@link com.java.system.agent.runtime.application.BoundedAnalysisLoop} 在迴圈的 goal 階段
 * 呼叫，回傳值不改變狀態，只表達迴圈該繼續還是終止</p>
 */
public interface GoalEvaluator {

    GoalEvaluation evaluate(Goal goal, AttemptState state, Optional<GoalBlockReason> blocker);
}
