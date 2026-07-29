package com.java.system.agent.capability.planning;

import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.runtime.domain.action.AgentAction;
import com.java.system.agent.runtime.domain.action.QueryAction;
import com.java.system.agent.runtime.domain.capability.CapabilityPolicy;
import com.java.system.agent.runtime.domain.handle.CapabilityHandle;
import com.java.system.agent.runtime.port.out.AgentPromptContext;
import org.springframework.ai.tool.ToolCallback;

import java.util.Objects;
import java.util.Map;

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
        this.callback = PlanningToolRegistration.callback(policy.name(), "Agent QUERY capability",
                requiredSchemaFactory.schemaFor(planningInputType));
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

    @Override
    public boolean isIssued(AgentPromptContext context) {
        return context.issuedCapabilities().containsValue(policy);
    }

    @Override
    public AgentAction toAction(P input, AgentPromptContext context, CanonicalCapabilityPayloadCodec payloadCodec) {
        CapabilityHandle capability = context.issuedCapabilities().entrySet().stream()
                .filter(entry -> entry.getValue().equals(policy))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("planning tool was not issued"));
        QueryPlanningSelection<E> selection = mapper.map(input);
        return new QueryAction(capability, selection.candidateReferences(), selection.questionToResolve(),
                payloadCodec.encode(selection.executionInput()), selection.rationale());
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
