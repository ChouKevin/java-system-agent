package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.run.AnalysisRunId;

public interface AnalysisCancellationPort {

    boolean isCancellationRequested(AnalysisRunId runId);
}
