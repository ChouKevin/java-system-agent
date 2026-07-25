package com.java.system.agent.runtime.application;

import com.java.system.agent.runtime.domain.AnalysisState;

public interface StateReducer {

    StateTransition reduce(AnalysisState currentState, AnalysisEvent event);
}
