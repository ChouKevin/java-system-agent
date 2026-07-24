package com.java.system.agent.analysis.port.out;

import com.java.system.agent.analysis.domain.AnalysisRunId;

public interface AnalysisCancellationPort {

    boolean isCancellationRequested(AnalysisRunId runId);
}
