package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.run.ExecutionDeferral;

import java.util.Objects;

/**
 * 外部 provider 要求延後執行時跨出 adapter 的安全訊號
 */
public final class ExternalExecutionDeferredException extends RuntimeException {

    private final ExecutionDeferral deferral;

    public ExternalExecutionDeferredException(ExecutionDeferral deferral) {
        super("external execution deferred");
        this.deferral = Objects.requireNonNull(deferral, "execution deferral must not be null");
    }

    public ExecutionDeferral deferral() {
        return deferral;
    }
}
