package com.java.system.agent.runtime.application;

import com.java.system.agent.runtime.domain.AnalysisState;
import com.java.system.agent.runtime.domain.Goal;

import java.util.Optional;

public interface GoalEvaluator {

    GoalEvaluation evaluate(Goal goal, AnalysisState state, Optional<GoalBlocker> blocker);
}
