package com.java.system.agent.analysis.port.out;

import com.java.system.agent.analysis.domain.AnalysisAttemptId;
import com.java.system.agent.analysis.domain.AnalysisRunId;

public interface AnalysisAttemptIdGenerator {

    AnalysisAttemptId nextAttemptId(AnalysisRunId runId, int attemptNumber);
}
