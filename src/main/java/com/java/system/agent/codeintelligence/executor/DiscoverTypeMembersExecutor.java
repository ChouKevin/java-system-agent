package com.java.system.agent.codeintelligence.executor;
import com.java.system.agent.capability.spi.CapabilityExecutionContext;
import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.codeintelligence.planning.DiscoverTypeMembersExecutionInput;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import java.util.Objects;
/** 將型別成員探索 capability 委派到 Java Semantic Service */
public final class DiscoverTypeMembersExecutor implements CapabilityExecutor<DiscoverTypeMembersExecutionInput> {
    private final JavaSemanticServiceHttpAdapter adapter;
    public DiscoverTypeMembersExecutor(JavaSemanticServiceHttpAdapter adapter) { this.adapter = Objects.requireNonNull(adapter, "Java Semantic Service adapter must not be null"); }
    @Override public CapabilityExecutionResult execute(CapabilityExecutionContext context, DiscoverTypeMembersExecutionInput input) { return adapter.discoverTypeMembers(context, input); }
}
