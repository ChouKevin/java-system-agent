package com.java.system.agent.capability.planning;

import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.action.AnswerAction;
import com.java.system.agent.answering.port.out.AgentPromptContext;

import java.util.Objects;
import java.util.function.Function;

/**
 * 將固定 ANSWER planning tool 的 typed input 與 mapper 綁為單一 registration
 */
public final class AnswerPlanningToolRegistration<I> implements PlanningToolRegistration<I> {

    private final String name;
    private final Class<I> planningInputType;
    private final Function<I, AnswerAction> mapper;
    private final PlanningToolDescriptor descriptor;

    public AnswerPlanningToolRegistration(
            String name,
            Class<I> planningInputType,
            Function<I, AnswerAction> mapper) {
        this.name = Objects.requireNonNull(name, "answer planning tool name must not be null");
        this.planningInputType = Objects.requireNonNull(planningInputType, "answer planning input type must not be null");
        this.mapper = Objects.requireNonNull(mapper, "answer planning mapper must not be null");
        this.descriptor = PlanningToolDescriptor.core(PlanningToolCategory.ANSWER, this.name);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return "Before submitting the Agent ANSWER action, Evidence coverage by capability must contain evidence handles "
                + "for every explicitly requested evidence type; cite those handles. "
                + "Do not substitute another evidence type for a missing one.";
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
