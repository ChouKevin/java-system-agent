package com.java.system.agent.capability.planning;

import com.java.system.agent.runtime.domain.action.AgentAction;
import com.java.system.agent.runtime.domain.action.AnswerAction;
import com.java.system.agent.runtime.port.out.AgentPromptContext;
import org.springframework.ai.tool.ToolCallback;

import java.util.Objects;
import java.util.function.Function;

/**
 * 將固定 ANSWER planning tool 的 typed input 與 mapper 綁為單一 registration
 */
public final class AnswerPlanningToolRegistration<I> implements PlanningToolRegistration<I> {

    private final String name;
    private final Class<I> planningInputType;
    private final Function<I, AnswerAction> mapper;
    private final ToolCallback callback;

    public AnswerPlanningToolRegistration(
            String name,
            Class<I> planningInputType,
            Function<I, AnswerAction> mapper,
            PlanningToolSchemaFactory schemaFactory) {
        this.name = Objects.requireNonNull(name, "answer planning tool name must not be null");
        this.planningInputType = Objects.requireNonNull(planningInputType, "answer planning input type must not be null");
        this.mapper = Objects.requireNonNull(mapper, "answer planning mapper must not be null");
        PlanningToolSchemaFactory requiredSchemaFactory = Objects.requireNonNull(
                schemaFactory, "planning schema factory must not be null");
        this.callback = PlanningToolRegistration.callback(name, "Agent ANSWER action",
                requiredSchemaFactory.schemaFor(planningInputType));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Class<I> planningInputType() {
        return planningInputType;
    }

    @Override
    public ToolCallback callback() {
        return callback;
    }

    @Override
    public AgentAction toAction(I input, AgentPromptContext context, CanonicalCapabilityPayloadCodec payloadCodec) {
        return mapper.apply(input);
    }
}
