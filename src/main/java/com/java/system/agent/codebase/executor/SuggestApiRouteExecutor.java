package com.java.system.agent.codebase.executor;

import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.codebase.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.runtime.domain.capability.CapabilityPolicy;
import com.java.system.agent.runtime.port.out.CapabilityExecutionContractException;
import com.java.system.agent.runtime.port.out.CapabilityExecutionResult;
import com.java.system.agent.runtime.port.out.CapabilityInvocation;

import java.util.Objects;

/**
 * 將 suggest-api-route capability 精確委派到 Java Semantic Service 的單一 read operation
 */
public final class SuggestApiRouteExecutor implements CapabilityExecutor {

    private final CapabilityPolicy capability;
    private final JavaSemanticServiceHttpAdapter adapter;

    public SuggestApiRouteExecutor(CapabilityPolicy capability, JavaSemanticServiceHttpAdapter adapter) {
        this.capability = Objects.requireNonNull(capability, "capability must not be null");
        this.adapter = Objects.requireNonNull(adapter, "Java Semantic Service adapter must not be null");
    }

    @Override
    public CapabilityPolicy capability() {
        return capability;
    }

    @Override
    public CapabilityExecutionResult execute(CapabilityInvocation invocation) {
        requireExactCapability(invocation);
        return adapter.suggestApiRoute(invocation);
    }

    private void requireExactCapability(CapabilityInvocation invocation) {
        Objects.requireNonNull(invocation, "capability invocation must not be null");
        if (!capability.equals(invocation.capability())) {
            throw new CapabilityExecutionContractException("capability invocation does not match suggest-api-route executor");
        }
    }
}
