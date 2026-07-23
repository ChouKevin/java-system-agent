package com.java.system.agent.analysis.port.out;

import com.java.system.agent.analysis.domain.AnalysisState;

public interface AnalysisTransitionPort<T> {

    AnalysisState commit(T transition);
}
