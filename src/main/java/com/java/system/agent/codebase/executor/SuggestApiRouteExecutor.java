package com.java.system.agent.codebase.executor;

import com.java.system.agent.capability.spi.CapabilityExecutionContext;
import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.codebase.planning.SuggestApiRouteExecutionInput;
import com.java.system.agent.codebase.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.runtime.port.out.CapabilityExecutionResult;

import java.util.Objects;

/**
 * 將 suggest-api-route capability 精確委派到 Java Semantic Service 的單一 read operation
 */
public final class SuggestApiRouteExecutor implements CapabilityExecutor<SuggestApiRouteExecutionInput> {

    private final JavaSemanticServiceHttpAdapter adapter;

    public SuggestApiRouteExecutor(JavaSemanticServiceHttpAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "Java Semantic Service adapter must not be null");
    }

    @Override
    public CapabilityExecutionResult execute(CapabilityExecutionContext context, SuggestApiRouteExecutionInput input) {
        return adapter.suggestApiRoute(context, input);
    }
}
