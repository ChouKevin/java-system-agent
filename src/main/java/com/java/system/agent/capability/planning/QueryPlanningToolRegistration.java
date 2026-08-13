package com.java.system.agent.capability.planning;

import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.port.out.AgentPromptContext;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 將一個 QUERY policy、typed planning mapper、payload type 與 typed executor 綁為唯一擴充單位。
 */
public final class QueryPlanningToolRegistration<P, E>
        implements PlanningToolRegistration<P>, QueryCapabilityRegistration<E> {

    private final CapabilityPolicy policy;
    private final Class<P> planningInputType;
    private final Class<E> executionInputType;
    private final CapabilityExecutor<E> executor;
    private final Optional<String> guidanceId;
    private final PlanningToolDescriptor descriptor;
    private final QueryPlanningMapper<P, E> mapper;
    private final CanonicalCapabilityPayloadCodec payloadCodec;

    public QueryPlanningToolRegistration(
            CapabilityPolicy policy,
            Class<P> planningInputType,
            Class<E> executionInputType,
            QueryPlanningMapper<P, E> mapper,
            CapabilityExecutor<E> executor,
            CanonicalCapabilityPayloadCodec payloadCodec) {
        this(policy, planningInputType, executionInputType, mapper, executor, payloadCodec, Optional.empty());
    }

    public QueryPlanningToolRegistration(
            CapabilityPolicy policy,
            Class<P> planningInputType,
            Class<E> executionInputType,
            QueryPlanningMapper<P, E> mapper,
            CapabilityExecutor<E> executor,
            CanonicalCapabilityPayloadCodec payloadCodec,
            Optional<String> guidanceId) {
        this.policy = Objects.requireNonNull(policy, "planning registration policy must not be null");
        this.planningInputType = Objects.requireNonNull(planningInputType, "planning input type must not be null");
        this.executionInputType = Objects.requireNonNull(executionInputType, "execution input type must not be null");
        this.executor = Objects.requireNonNull(executor, "capability executor must not be null");
        this.guidanceId = Objects.requireNonNull(guidanceId, "planning tool guidance id must not be null");
        this.mapper = Objects.requireNonNull(mapper, "planning mapper must not be null");
        this.payloadCodec = Objects.requireNonNull(payloadCodec, "capability payload codec must not be null");
        this.descriptor = PlanningToolDescriptor.query(PlanningToolCategory.QUERY, this.policy, this.guidanceId);
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
    public Class<P> planningInputType() {
        return planningInputType;
    }

    @Override
    public boolean isIssued(AgentPromptContext context) {
        Objects.requireNonNull(context, "agent prompt context must not be null");
        return currentCapability(context).isPresent();
    }

    @Override
    public AgentAction toAction(P input, AgentPromptContext context) {
        Objects.requireNonNull(input, "planning input must not be null");
        Objects.requireNonNull(context, "agent prompt context must not be null");
        QueryPlanningSelection<E> selection = mapper.map(input);
        CapabilityHandle capability = currentCapability(context).orElseThrow(
                QueryPlanningToolRegistration::invalidCapabilityBinding);
        CapabilityInputPayload payload = payloadCodec.encode(selection.executionInput());
        return new QueryAction(capability, selection.questionToResolve(), payload, selection.rationale());
    }

    public CapabilityPolicy policy() {
        return policy;
    }

    public Class<E> executionInputType() {
        return executionInputType;
    }

    public CapabilityExecutor<E> executor() {
        return executor;
    }

    private Optional<CapabilityHandle> currentCapability(AgentPromptContext context) {
        return context.issuedCapabilities().entrySet().stream()
                .filter(entry -> entry.getValue().equals(policy))
                .filter(entry -> hasCurrentBinding(entry.getKey().binding(), context))
                .filter(entry -> !entry.getKey().binding().revisionVector().entries().isEmpty())
                .map(Map.Entry::getKey)
                .findFirst();
    }

    private static boolean hasCurrentBinding(HandleBinding binding, AgentPromptContext context) {
        return binding.runId().equals(context.runId())
                && binding.attemptId().equals(context.attemptId());
    }

    private static PlanningToolInputException invalidCapabilityBinding() {
        return PlanningToolInputException.safeDiagnostic("reason=CAPABILITY_BINDING");
    }
}
