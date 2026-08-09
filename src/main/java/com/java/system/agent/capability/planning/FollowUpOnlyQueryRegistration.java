package com.java.system.agent.capability.planning;

import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.handle.CandidateHandleRef;
import com.java.system.agent.answering.port.out.AgentPromptContext;

import java.util.List;
import java.util.Map;
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
        return "Execute one provider-bound follow-up for " + policy.name()
                + " by opaque FOLLOW_UP candidate handle";
    }

    @Override
    public Class<FollowUpPlanningInput> planningInputType() {
        return FollowUpPlanningInput.class;
    }

    @Override
    public boolean isIssued(AgentPromptContext context) {
        Objects.requireNonNull(context, "agent prompt context must not be null");
        return context.issuedCandidates().entrySet().stream()
                .anyMatch(candidate -> ProviderBoundFollowUp
                        .targetCapability(context, candidate, policy)
                        .isPresent());
    }

    @Override
    public AgentAction toAction(FollowUpPlanningInput input, AgentPromptContext context) {
        Objects.requireNonNull(input, "follow-up planning input must not be null");
        Objects.requireNonNull(context, "agent prompt context must not be null");
        Map.Entry<CandidateHandle, IssuedCandidate> selected = context.issuedCandidates().entrySet().stream()
                .filter(entry -> entry.getKey().value().equals(input.followUpCandidateHandle()))
                .findFirst()
                .orElseThrow(PlanningToolInputException::new);
        CapabilityHandle capability = ProviderBoundFollowUp.targetCapability(context, selected, policy)
                .orElseThrow(PlanningToolInputException::new);
        CandidateHandleRef reference = new CandidateHandleRef(selected.getKey().value());
        CapabilityInputPayload payload = ProviderBoundFollowUp.boundPayload(
                context, capability, policy, List.of(reference)).orElseThrow(PlanningToolInputException::new);
        return new QueryAction(capability, List.of(reference), input.questionToResolve(), payload, input.rationale());
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
