package com.java.system.agent.runtime.adapter.fake;

import com.java.system.agent.analysis.domain.AnalysisRunId;
import com.java.system.agent.analysis.port.out.AnalysisCancellationPort;

import java.util.Objects;

public final class FakeCancellationAdapter implements AnalysisCancellationPort {

    private long cancellationAfterChecks = Long.MAX_VALUE;
    private long checkCount;

    public synchronized FakeCancellationAdapter requestCancellationAfter(long completedChecks) {
        if (completedChecks < 0) {
            throw new IllegalArgumentException("completed checks must not be negative");
        }
        cancellationAfterChecks = completedChecks;
        return this;
    }

    @Override
    public synchronized boolean isCancellationRequested(AnalysisRunId runId) {
        Objects.requireNonNull(runId, "analysis run ID must not be null");
        checkCount++;
        return checkCount > cancellationAfterChecks;
    }

    public synchronized long checkCount() {
        return checkCount;
    }
}
