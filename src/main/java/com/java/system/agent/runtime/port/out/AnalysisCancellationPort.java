package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.AnalysisRunId;

public interface AnalysisCancellationPort {

    boolean isCancellationRequested(AnalysisRunId runId);
}
