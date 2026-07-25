package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.run.AttemptState;

public interface AnalysisTransitionPort<T> {

    AttemptState commit(T transition);
}
