package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.run.AnalysisRunId;

/**
 * 唯讀查詢 durable cancellation marker 的外部邊界，找不到 run 時回傳 false
 */
public interface AnalysisCancellationPort {

    boolean isCancellationRequested(AnalysisRunId runId);
}
