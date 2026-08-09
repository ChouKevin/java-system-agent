package com.java.system.agent.capability.planning;

import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.port.out.AgentPromptContext;

import java.util.Objects;

/**
 * 將一個 QUERY policy、planning mapper、payload type 與 typed executor 綁為唯一擴充單位
 */
public final class QueryPlanningToolRegistration<P, E>
        implements PlanningToolRegistration<P>, QueryCapabilityRegistration<E> {

    private final CapabilityPolicy policy;
    private final Class<P> planningInputType;
    private final Class<E> executionInputType;
    private final QueryPlanningMapper<P, E> mapper;
    private final CapabilityExecutor<E> executor;
    private final CanonicalCapabilityPayloadCodec payloadCodec;

    public QueryPlanningToolRegistration(
            CapabilityPolicy policy,
            Class<P> planningInputType,
            Class<E> executionInputType,
            QueryPlanningMapper<P, E> mapper,
            CapabilityExecutor<E> executor,
            CanonicalCapabilityPayloadCodec payloadCodec) {
        this.policy = Objects.requireNonNull(policy, "planning registration policy must not be null");
        this.planningInputType = Objects.requireNonNull(planningInputType, "planning input type must not be null");
        this.executionInputType = Objects.requireNonNull(executionInputType, "execution input type must not be null");
        this.mapper = Objects.requireNonNull(mapper, "planning mapper must not be null");
        this.executor = Objects.requireNonNull(executor, "capability executor must not be null");
        this.payloadCodec = Objects.requireNonNull(payloadCodec, "capability payload codec must not be null");
    }

    @Override
    public String name() {
        return policy.name();
    }

    @Override
    public String description() {
        String candidateCardinality = policy.minimumCandidates() == policy.maximumCandidates()
                ? "exactly " + policy.minimumCandidates()
                : "between " + policy.minimumCandidates() + " and " + policy.maximumCandidates();
        String acceptedKinds = String.join(", ", policy.acceptedCandidateKinds().stream()
                .map(Enum::name)
                .sorted()
                .toList());
        String followUpConstraint = policy.acceptedCandidateKinds().contains(CandidateKind.FOLLOW_UP)
                ? " A FOLLOW_UP candidate must target " + policy.name() + "@" + policy.version() + "."
                : "";
        return "Agent QUERY capability. candidateHandles must contain " + candidateCardinality
                + " candidate of kinds [" + acceptedKinds + "]." + followUpConstraint;
    }

    @Override
    public Class<P> planningInputType() {
        return planningInputType;
    }

    @Override
    public boolean isIssued(AgentPromptContext context) {
        return context.issuedCapabilities().containsValue(policy);
    }

    @Override
    public AgentAction toAction(P input, AgentPromptContext context) {
        CapabilityHandle capability = context.issuedCapabilities().entrySet().stream()
                .filter(entry -> entry.getValue().equals(policy))
                .map(mapEntry -> mapEntry.getKey())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("planning tool was not issued"));
        QueryPlanningSelection<E> selection = mapper.map(input);
        CapabilityInputPayload payload = ProviderBoundFollowUp.boundPayload(context, capability, policy,
                        selection.candidateReferences())
                .orElseGet(() -> payloadCodec.encode(selection.executionInput()));
        return new QueryAction(capability, selection.candidateReferences(), selection.questionToResolve(),
                payload, selection.rationale());
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
