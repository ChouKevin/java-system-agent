package com.java.system.agent.capability.dispatch;

import com.java.system.agent.capability.planning.PlanningToolRegistry;
import com.java.system.agent.runtime.port.out.CapabilityExecutionPort;
import com.java.system.agent.runtime.port.out.CapabilityExecutionResult;
import com.java.system.agent.runtime.port.out.CapabilityInvocation;

import java.util.Objects;

/**
 * 依 runtime 已驗證 identity 解析 registration、rehydrate payload 並委派 typed executor 的 outbound adapter
 */
public final class CapabilityExecutionDispatcher implements CapabilityExecutionPort {

    private final PlanningToolRegistry registry;

    public CapabilityExecutionDispatcher(PlanningToolRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "planning tool registry must not be null");
    }

    @Override
    public CapabilityExecutionResult execute(CapabilityInvocation invocation) {
        return registry.execute(Objects.requireNonNull(invocation, "capability invocation must not be null"));
    }
}
