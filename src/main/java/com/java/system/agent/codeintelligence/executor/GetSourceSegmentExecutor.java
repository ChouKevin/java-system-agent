package com.java.system.agent.codeintelligence.executor;

import com.java.system.agent.capability.spi.CapabilityExecutionContext;
import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.codeintelligence.planning.GetSourceSegmentExecutionInput;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;

import java.util.Objects;

/** 將來源片段 capability 委派到 Java Semantic Service */
public final class GetSourceSegmentExecutor implements CapabilityExecutor<GetSourceSegmentExecutionInput> {

    private final JavaSemanticServiceHttpAdapter adapter;

    public GetSourceSegmentExecutor(JavaSemanticServiceHttpAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "Java Semantic Service adapter must not be null");
    }

    @Override
    public CapabilityExecutionResult execute(CapabilityExecutionContext context, GetSourceSegmentExecutionInput input) {
        return adapter.getSourceSegment(context, input);
    }
}
