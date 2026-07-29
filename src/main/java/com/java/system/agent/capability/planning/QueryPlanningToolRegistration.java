package com.java.system.agent.capability.planning;

import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.runtime.domain.capability.CapabilityPolicy;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Objects;

/**
 * 將一個 QUERY policy、planning mapper、payload type 與 typed executor 綁為唯一擴充單位
 */
public final class QueryPlanningToolRegistration<P, E> implements PlanningToolRegistration<P> {

    private final CapabilityPolicy policy;
    private final Class<P> planningInputType;
    private final Class<E> executionInputType;
    private final QueryPlanningMapper<P, E> mapper;
    private final CapabilityExecutor<E> executor;
    private final ToolCallback callback;

    public QueryPlanningToolRegistration(
            CapabilityPolicy policy,
            Class<P> planningInputType,
            Class<E> executionInputType,
            QueryPlanningMapper<P, E> mapper,
            CapabilityExecutor<E> executor,
            PlanningToolSchemaFactory schemaFactory) {
        this.policy = Objects.requireNonNull(policy, "planning registration policy must not be null");
        this.planningInputType = Objects.requireNonNull(planningInputType, "planning input type must not be null");
        this.executionInputType = Objects.requireNonNull(executionInputType, "execution input type must not be null");
        this.mapper = Objects.requireNonNull(mapper, "planning mapper must not be null");
        this.executor = Objects.requireNonNull(executor, "capability executor must not be null");
        PlanningToolSchemaFactory requiredSchemaFactory = Objects.requireNonNull(
                schemaFactory, "planning schema factory must not be null");
        this.callback = new SchemaToolCallback(policy.name(), requiredSchemaFactory.schemaFor(planningInputType));
    }

    @Override
    public String name() {
        return policy.name();
    }

    @Override
    public Class<P> planningInputType() {
        return planningInputType;
    }

    @Override
    public ToolCallback callback() {
        return callback;
    }

    public CapabilityPolicy policy() {
        return policy;
    }

    public Class<E> executionInputType() {
        return executionInputType;
    }

    public QueryPlanningMapper<P, E> mapper() {
        return mapper;
    }

    public CapabilityExecutor<E> executor() {
        return executor;
    }

    /**
     * 由 registration 依 planning input canonical schema 建立的 provider callback
     */
    private record SchemaToolCallback(ToolDefinition definition) implements ToolCallback {

        SchemaToolCallback(String name, String schema) {
            this(DefaultToolDefinition.builder().name(name).description("Agent QUERY capability").inputSchema(schema).build());
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            return toolInput;
        }
    }
}
