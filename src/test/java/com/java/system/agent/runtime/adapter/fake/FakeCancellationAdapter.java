package com.java.system.agent.runtime.adapter.fake;

import com.java.system.agent.runtime.domain.AnalysisRunId;
import com.java.system.agent.runtime.port.out.AnalysisCancellationPort;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class FakeCancellationAdapter implements AnalysisCancellationPort {

    private final Map<AnalysisRunId, RunState> states = new HashMap<>();

    public synchronized FakeCancellationAdapter requestCancellationAfter(
            AnalysisRunId runId,
            long completedChecks) {
        Objects.requireNonNull(runId, "analysis run ID must not be null");
        if (completedChecks < 0) {
            throw new IllegalArgumentException("completed checks must not be negative");
        }
        states.computeIfAbsent(runId, ignored -> new RunState()).cancellationAfterChecks = completedChecks;
        return this;
    }

    @Override
    public synchronized boolean isCancellationRequested(AnalysisRunId runId) {
        Objects.requireNonNull(runId, "analysis run ID must not be null");
        RunState state = states.computeIfAbsent(runId, ignored -> new RunState());
        state.checkCount++;
        return state.checkCount > state.cancellationAfterChecks;
    }

    public synchronized long checkCount(AnalysisRunId runId) {
        Objects.requireNonNull(runId, "analysis run ID must not be null");
        RunState state = states.get(runId);
        return Objects.isNull(state) ? 0 : state.checkCount;
    }

    private static final class RunState {

        private long cancellationAfterChecks = Long.MAX_VALUE;
        private long checkCount;
    }
}
