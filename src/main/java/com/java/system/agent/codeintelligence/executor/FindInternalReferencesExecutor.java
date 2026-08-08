package com.java.system.agent.codeintelligence.executor;
import com.java.system.agent.capability.spi.CapabilityExecutionContext;
import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.codeintelligence.planning.FindInternalReferencesExecutionInput;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import java.util.Objects;
/** 將內部參照探索 capability 委派到 Java Semantic Service */
public final class FindInternalReferencesExecutor implements CapabilityExecutor<FindInternalReferencesExecutionInput> {
    private final JavaSemanticServiceHttpAdapter adapter;
    public FindInternalReferencesExecutor(JavaSemanticServiceHttpAdapter adapter) { this.adapter = Objects.requireNonNull(adapter, "Java Semantic Service adapter must not be null"); }
    @Override public CapabilityExecutionResult execute(CapabilityExecutionContext context, FindInternalReferencesExecutionInput input) { return adapter.findInternalReferences(context, input); }
}
