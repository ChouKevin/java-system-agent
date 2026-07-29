package com.java.system.agent.codeintelligence.executor;

import com.java.system.agent.capability.spi.CapabilityExecutionContext;
import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.codeintelligence.planning.ListEntryPointsExecutionInput;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;

import java.util.Objects;

/**
 * 將 list-entry-points capability 精確委派到 Java Semantic Service 的單一 read operation
 */
public final class ListEntryPointsExecutor implements CapabilityExecutor<ListEntryPointsExecutionInput> {

    private final JavaSemanticServiceHttpAdapter adapter;

    public ListEntryPointsExecutor(JavaSemanticServiceHttpAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "Java Semantic Service adapter must not be null");
    }

    @Override
    public CapabilityExecutionResult execute(CapabilityExecutionContext context, ListEntryPointsExecutionInput input) {
        return adapter.listEntryPoints(context, input);
    }
}
