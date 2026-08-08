package com.java.system.agent.codeintelligence.executor;
import com.java.system.agent.capability.spi.CapabilityExecutionContext;
import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.codeintelligence.planning.GetEvidenceSourceExecutionInput;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import java.util.Objects;
/** 將證據來源 capability 委派到 Java Semantic Service */
public final class GetEvidenceSourceExecutor implements CapabilityExecutor<GetEvidenceSourceExecutionInput> {
    private final JavaSemanticServiceHttpAdapter adapter;
    public GetEvidenceSourceExecutor(JavaSemanticServiceHttpAdapter adapter) { this.adapter = Objects.requireNonNull(adapter, "Java Semantic Service adapter must not be null"); }
    @Override public CapabilityExecutionResult execute(CapabilityExecutionContext context, GetEvidenceSourceExecutionInput input) { return adapter.getEvidenceSource(context, input); }
}
