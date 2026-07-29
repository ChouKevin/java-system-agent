package com.java.system.agent.capability.planning;

import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.runtime.domain.capability.CapabilityPolicy;
import org.springframework.ai.tool.ToolCallback;

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
            ToolCallback callback) {
        this.policy = Objects.requireNonNull(policy, "planning registration policy must not be null");
        this.planningInputType = Objects.requireNonNull(planningInputType, "planning input type must not be null");
        this.executionInputType = Objects.requireNonNull(executionInputType, "execution input type must not be null");
        this.mapper = Objects.requireNonNull(mapper, "planning mapper must not be null");
        this.executor = Objects.requireNonNull(executor, "capability executor must not be null");
        this.callback = Objects.requireNonNull(callback, "planning callback must not be null");
        if (!policy.name().equals(callback.getToolDefinition().name())) {
            throw new IllegalArgumentException("planning callback name must equal capability policy name");
        }
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
}
