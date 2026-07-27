package com.java.system.agent.capability.dispatch;

import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.runtime.port.out.CapabilityExecutionContractException;
import com.java.system.agent.runtime.port.out.CapabilityExecutionPort;
import com.java.system.agent.runtime.port.out.CapabilityExecutionResult;
import com.java.system.agent.runtime.port.out.CapabilityInvocation;

import java.util.Objects;

/**
 * 依 runtime 已驗證 capability descriptor 精確委派執行器的 outbound adapter
 */
public final class CapabilityExecutionDispatcher implements CapabilityExecutionPort {

    private final CapabilityExecutorRegistry registry;

    public CapabilityExecutionDispatcher(CapabilityExecutorRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "capability executor registry must not be null");
    }

    @Override
    public CapabilityExecutionResult execute(CapabilityInvocation invocation) {
        Objects.requireNonNull(invocation, "capability invocation must not be null");
        CapabilityExecutor executor = registry.executors().get(invocation.capability());
        if (Objects.isNull(executor)) {
            throw new CapabilityExecutionContractException("validated capability has no registered executor");
        }
        CapabilityExecutionResult result = executor.execute(invocation);
        if (Objects.isNull(result)) {
            throw new CapabilityExecutionContractException("capability executor must return a result");
        }
        return result;
    }
}
