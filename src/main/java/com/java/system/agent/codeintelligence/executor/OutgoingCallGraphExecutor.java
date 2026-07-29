package com.java.system.agent.codeintelligence.executor;

import com.java.system.agent.capability.spi.CapabilityExecutionContext;
import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.codeintelligence.planning.OutgoingCallGraphExecutionInput;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;

import java.util.Objects;

/**
 * 將 outgoing-call-graph capability 精確委派到 Java Semantic Service 的單一 read operation
 */
public final class OutgoingCallGraphExecutor implements CapabilityExecutor<OutgoingCallGraphExecutionInput> {

    private final JavaSemanticServiceHttpAdapter adapter;

    public OutgoingCallGraphExecutor(JavaSemanticServiceHttpAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "Java Semantic Service adapter must not be null");
    }

    @Override
    public CapabilityExecutionResult execute(CapabilityExecutionContext context, OutgoingCallGraphExecutionInput input) {
        return adapter.outgoingCallGraph(context, input);
    }
}
