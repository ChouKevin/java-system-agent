package com.java.system.agent.codeintelligence.executor;

import com.java.system.agent.capability.spi.CapabilityExecutionContext;
import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.codeintelligence.planning.GetMethodSourceExecutionInput;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;

import java.util.Objects;

/** 將方法來源 capability 委派到 Java Semantic Service */
public final class GetMethodSourceExecutor implements CapabilityExecutor<GetMethodSourceExecutionInput> {

    private final JavaSemanticServiceHttpAdapter adapter;

    public GetMethodSourceExecutor(JavaSemanticServiceHttpAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "Java Semantic Service adapter must not be null");
    }

    @Override
    public CapabilityExecutionResult execute(CapabilityExecutionContext context, GetMethodSourceExecutionInput input) {
        return adapter.getMethodSource(context, input);
    }
}
