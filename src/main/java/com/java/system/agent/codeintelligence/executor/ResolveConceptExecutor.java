package com.java.system.agent.codeintelligence.executor;

import com.java.system.agent.capability.spi.CapabilityExecutionContext;
import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.codeintelligence.planning.ResolveConceptExecutionInput;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;

import java.util.Objects;

/** 將概念解析 capability 委派到 Java Semantic Service */
public final class ResolveConceptExecutor implements CapabilityExecutor<ResolveConceptExecutionInput> {

    private final JavaSemanticServiceHttpAdapter adapter;

    public ResolveConceptExecutor(JavaSemanticServiceHttpAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "Java Semantic Service adapter must not be null");
    }

    @Override
    public CapabilityExecutionResult execute(CapabilityExecutionContext context, ResolveConceptExecutionInput input) {
        return adapter.resolveConcept(context, input);
    }
}
