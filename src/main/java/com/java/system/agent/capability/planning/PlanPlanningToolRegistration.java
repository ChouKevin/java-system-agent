package com.java.system.agent.capability.planning;

import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.action.PlanAction;
import com.java.system.agent.answering.port.out.AgentPromptContext;

import java.util.Objects;
import java.util.function.Function;

/**
 * 將固定 PLAN planning tool 的 typed input 與 mapper 綁為單一 registration
 */
public final class PlanPlanningToolRegistration<I> implements PlanningToolRegistration<I> {

    private final String name;
    private final Class<I> planningInputType;
    private final Function<I, PlanAction> mapper;
    private final PlanningToolDescriptor descriptor;

    public PlanPlanningToolRegistration(
            String name,
            Class<I> planningInputType,
            Function<I, PlanAction> mapper) {
        this.name = Objects.requireNonNull(name, "plan planning tool name must not be null");
        this.planningInputType = Objects.requireNonNull(planningInputType, "plan planning input type must not be null");
        this.mapper = Objects.requireNonNull(mapper, "plan planning mapper must not be null");
        this.descriptor = PlanningToolDescriptor.core(PlanningToolCategory.PLAN, this.name);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public PlanningToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public Class<I> planningInputType() {
        return planningInputType;
    }

    @Override
    public AgentAction toAction(I input, AgentPromptContext context) {
        return mapper.apply(input);
    }
}
