package com.java.system.agent.codebase.executor;

import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.codebase.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.runtime.domain.capability.CapabilityDescriptor;
import com.java.system.agent.runtime.port.out.CapabilityExecutionContractException;
import com.java.system.agent.runtime.port.out.CapabilityExecutionResult;
import com.java.system.agent.runtime.port.out.CapabilityInvocation;

import java.util.Objects;

/**
 * 將 list-entry-points capability 精確委派到 Java Semantic Service 的單一 read operation
 */
public final class ListEntryPointsExecutor implements CapabilityExecutor {

    private final CapabilityDescriptor capability;
    private final JavaSemanticServiceHttpAdapter adapter;

    public ListEntryPointsExecutor(CapabilityDescriptor capability, JavaSemanticServiceHttpAdapter adapter) {
        this.capability = Objects.requireNonNull(capability, "capability must not be null");
        this.adapter = Objects.requireNonNull(adapter, "Java Semantic Service adapter must not be null");
    }

    @Override
    public CapabilityDescriptor capability() {
        return capability;
    }

    @Override
    public CapabilityExecutionResult execute(CapabilityInvocation invocation) {
        requireExactCapability(invocation);
        return adapter.listEntryPoints(invocation);
    }

    private void requireExactCapability(CapabilityInvocation invocation) {
        Objects.requireNonNull(invocation, "capability invocation must not be null");
        if (!capability.equals(invocation.capability())) {
            throw new CapabilityExecutionContractException("capability invocation does not match list-entry-points executor");
        }
    }
}
