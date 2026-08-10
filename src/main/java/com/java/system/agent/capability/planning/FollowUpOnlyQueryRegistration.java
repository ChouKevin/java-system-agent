package com.java.system.agent.capability.planning;

import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.handle.CandidateHandleRef;
import com.java.system.agent.answering.port.out.AgentPromptContext;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 僅接受已綁定 follow-up payload 執行的 QUERY registration，不可直接由模型規劃
 */
public final class FollowUpOnlyQueryRegistration<E>
        implements PlanningToolRegistration<FollowUpPlanningInput>, QueryCapabilityRegistration<E> {

    private static final String INVALID_FOLLOW_UP_SELECTION = "reason=FOLLOW_UP_SELECTION; "
            + "invalidFields=[followUpCandidateHandle]; "
            + "constraints=[followUpCandidateHandle:CurrentlyAuthorizedFollowUp]";

    private final CapabilityPolicy policy;
    private final Class<E> executionInputType;
    private final CapabilityExecutor<E> executor;
    private final Optional<String> guidanceId;
    private final PlanningToolDescriptor descriptor;

    public FollowUpOnlyQueryRegistration(
            CapabilityPolicy policy,
            Class<E> executionInputType,
            CapabilityExecutor<E> executor) {
        this(policy, executionInputType, executor, Optional.empty());
    }

    public FollowUpOnlyQueryRegistration(
            CapabilityPolicy policy,
            Class<E> executionInputType,
            CapabilityExecutor<E> executor,
            Optional<String> guidanceId) {
        this.policy = Objects.requireNonNull(policy, "follow-up-only registration policy must not be null");
        this.executionInputType = Objects.requireNonNull(executionInputType,
                "follow-up-only execution input type must not be null");
        this.executor = Objects.requireNonNull(executor, "follow-up-only capability executor must not be null");
        this.guidanceId = Objects.requireNonNull(guidanceId, "planning tool guidance id must not be null");
        this.descriptor = PlanningToolDescriptor.query(PlanningToolCategory.FOLLOW_UP_QUERY, this.policy, this.guidanceId);
    }

    @Override
    public String name() {
        return policy.name();
    }

    @Override
    public PlanningToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public Class<FollowUpPlanningInput> planningInputType() {
        return FollowUpPlanningInput.class;
    }

    @Override
    public boolean isIssued(AgentPromptContext context) {
        Objects.requireNonNull(context, "agent prompt context must not be null");
        return context.issuedCandidates().entrySet().stream()
                .map(candidate -> new CandidateHandleRef(candidate.getKey().value()))
                .anyMatch(reference -> ProviderBoundFollowUp.selection(context, policy, reference).isPresent());
    }

    @Override
    public AgentAction toAction(FollowUpPlanningInput input, AgentPromptContext context) {
        Objects.requireNonNull(input, "follow-up planning input must not be null");
        Objects.requireNonNull(context, "agent prompt context must not be null");
        CandidateHandleRef reference = new CandidateHandleRef(input.followUpCandidateHandle());
        ProviderBoundFollowUp.Selection selection = ProviderBoundFollowUp.selection(context, policy, reference)
                .orElseThrow(FollowUpOnlyQueryRegistration::invalidFollowUpSelection);
        return new QueryAction(selection.capability(), List.of(selection.candidateReference()), input.questionToResolve(),
                selection.providerPayload(), input.rationale());
    }

    private static PlanningToolInputException invalidFollowUpSelection() {
        return PlanningToolInputException.safeDiagnostic(INVALID_FOLLOW_UP_SELECTION);
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
