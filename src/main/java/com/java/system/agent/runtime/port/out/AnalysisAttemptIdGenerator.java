package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;

public interface AnalysisAttemptIdGenerator {

    AnalysisAttemptId nextAttemptId(AnalysisRunId runId, int attemptNumber);
}
