package com.java.system.agent.codeintelligence.executor;
import com.java.system.agent.capability.spi.CapabilityExecutionContext;
import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.codeintelligence.planning.ResolveSourceSymbolExecutionInput;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import java.util.Objects;
/** 將來源符號解析 capability 委派到 Java Semantic Service */
public final class ResolveSourceSymbolExecutor implements CapabilityExecutor<ResolveSourceSymbolExecutionInput> {
    private final JavaSemanticServiceHttpAdapter adapter;
    public ResolveSourceSymbolExecutor(JavaSemanticServiceHttpAdapter adapter) { this.adapter = Objects.requireNonNull(adapter, "Java Semantic Service adapter must not be null"); }
    @Override public CapabilityExecutionResult execute(CapabilityExecutionContext context, ResolveSourceSymbolExecutionInput input) { return adapter.resolveSourceSymbol(context, input); }
}
