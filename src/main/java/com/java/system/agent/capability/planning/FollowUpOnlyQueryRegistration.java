package com.java.system.agent.capability.planning;

import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.port.out.AgentActionContractException;
import com.java.system.agent.answering.port.out.AgentPromptContext;

import java.util.Objects;

/**
 * 僅接受已綁定 follow-up payload 執行的 QUERY registration，不可直接由模型規劃
 */
public final class FollowUpOnlyQueryRegistration<E>
        implements PlanningToolRegistration<FollowUpPlanningInput>, QueryCapabilityRegistration<E> {

    private final CapabilityPolicy policy;
    private final Class<E> executionInputType;
    private final CapabilityExecutor<E> executor;

    public FollowUpOnlyQueryRegistration(
            CapabilityPolicy policy,
            Class<E> executionInputType,
            CapabilityExecutor<E> executor) {
        this.policy = Objects.requireNonNull(policy, "follow-up-only registration policy must not be null");
        this.executionInputType = Objects.requireNonNull(executionInputType,
                "follow-up-only execution input type must not be null");
        this.executor = Objects.requireNonNull(executor, "follow-up-only capability executor must not be null");
    }

    @Override
    public String name() {
        return policy.name();
    }

    @Override
    public String description() {
        return "Bound follow-up QUERY capability";
    }

    @Override
    public Class<FollowUpPlanningInput> planningInputType() {
        return FollowUpPlanningInput.class;
    }

    @Override
    public boolean isIssued(AgentPromptContext context) {
        Objects.requireNonNull(context, "agent prompt context must not be null");
        return false;
    }

    @Override
    public AgentAction toAction(FollowUpPlanningInput input, AgentPromptContext context) {
        throw new AgentActionContractException("follow-up-only query capability cannot be directly planned",
                new IllegalStateException("direct planning is prohibited"));
    }

    @Override
    public CapabilityPolicy policy() {
        return policy;
    }

    @Override
    public Class<E> executionInputType() {
        return executionInputType;
    }

    @Override
    public CapabilityExecutor<E> executor() {
        return executor;
    }
}
