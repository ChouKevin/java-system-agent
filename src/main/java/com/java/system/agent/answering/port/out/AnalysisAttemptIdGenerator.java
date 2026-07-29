package com.java.system.agent.answering.port.out;

import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;

public interface AnalysisAttemptIdGenerator {

    AnalysisAttemptId nextAttemptId(AnalysisRunId runId, int attemptSequence);
}
