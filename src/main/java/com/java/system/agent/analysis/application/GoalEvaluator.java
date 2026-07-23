package com.java.system.agent.analysis.application;

import com.java.system.agent.analysis.domain.AnalysisState;
import com.java.system.agent.analysis.domain.Goal;

import java.util.Optional;

public interface GoalEvaluator {

    GoalEvaluation evaluate(Goal goal, AnalysisState state, Optional<GoalBlocker> blocker);
}
