package com.java.system.agent.codeintelligence.executor;

import com.java.system.agent.capability.spi.CapabilityExecutionContext;
import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.codeintelligence.planning.IncomingCallGraphExecutionInput;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;

import java.util.Objects;

/**
 * 將 incoming-call-graph capability 精確委派到 Java Semantic Service 的單一 read operation
 */
public final class IncomingCallGraphExecutor implements CapabilityExecutor<IncomingCallGraphExecutionInput> {

    private final JavaSemanticServiceHttpAdapter adapter;

    public IncomingCallGraphExecutor(JavaSemanticServiceHttpAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "Java Semantic Service adapter must not be null");
    }

    @Override
    public CapabilityExecutionResult execute(CapabilityExecutionContext context, IncomingCallGraphExecutionInput input) {
        return adapter.incomingCallGraph(context, input);
    }
}
