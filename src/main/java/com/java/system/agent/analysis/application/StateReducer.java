package com.java.system.agent.analysis.application;

import com.java.system.agent.analysis.domain.AnalysisState;

public interface StateReducer {

    StateTransition reduce(AnalysisState currentState, AnalysisEvent event);
}
