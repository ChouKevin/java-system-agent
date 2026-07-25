package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.AnalysisState;

public interface AnalysisTransitionPort<T> {

    AnalysisState commit(T transition);
}
