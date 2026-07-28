package com.java.system.agent.runtime.port.in;

import com.java.system.agent.runtime.domain.run.ExecutionDeferral;

import java.util.Objects;

/**
 * 分析執行因外部容量限制而可安全延後的 inbound 訊號
 */
public final class AnalysisExecutionDeferredException extends RuntimeException {

    private final ExecutionDeferral deferral;

    public AnalysisExecutionDeferredException(ExecutionDeferral deferral) {
        super("analysis execution deferred");
        this.deferral = Objects.requireNonNull(deferral, "execution deferral must not be null");
    }

    public ExecutionDeferral deferral() {
        return deferral;
    }
}
