package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.AnalysisRunId;

public interface AnalysisAttemptIdGenerator {

    AnalysisAttemptId nextAttemptId(AnalysisRunId runId, int attemptNumber);
}
