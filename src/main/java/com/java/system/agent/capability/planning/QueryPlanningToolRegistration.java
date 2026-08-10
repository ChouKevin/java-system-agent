package com.java.system.agent.capability.planning;

import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.port.out.AgentPromptContext;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 將一個 QUERY policy、planning strategy、payload type 與 typed executor 綁為唯一擴充單位
 */
public final class QueryPlanningToolRegistration<P, E>
        implements PlanningToolRegistration<P>, QueryCapabilityRegistration<E> {

    private final CapabilityPolicy policy;
    private final Class<P> planningInputType;
    private final Class<E> executionInputType;
    private final CapabilityExecutor<E> executor;
    private final Optional<String> guidanceId;
    private final PlanningToolDescriptor descriptor;
    private final QueryPlanningStrategy<P, E> strategy;

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
        this(PlanningToolCategory.QUERY, policy, planningInputType, executionInputType, executor, guidanceId,
                new MapperQueryPlanningStrategy<>(policy, mapper, payloadCodec));
    }

    QueryPlanningToolRegistration(
            PlanningToolCategory category,
            CapabilityPolicy policy,
            Class<P> planningInputType,
            Class<E> executionInputType,
            CapabilityExecutor<E> executor,
            Optional<String> guidanceId,
            QueryPlanningStrategy<P, E> strategy) {
        this.category(category);
        this.policy = Objects.requireNonNull(policy, "planning registration policy must not be null");
        this.planningInputType = Objects.requireNonNull(planningInputType, "planning input type must not be null");
        this.executionInputType = Objects.requireNonNull(executionInputType, "execution input type must not be null");
        this.executor = Objects.requireNonNull(executor, "capability executor must not be null");
        this.guidanceId = Objects.requireNonNull(guidanceId, "planning tool guidance id must not be null");
        this.strategy = Objects.requireNonNull(strategy, "query planning strategy must not be null");
        this.descriptor = PlanningToolDescriptor.query(category, this.policy, this.guidanceId);
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
        return strategy.isIssued(context);
    }

    @Override
    public AgentAction toAction(P input, AgentPromptContext context) {
        return strategy.toAction(input, context);
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

    private static void category(PlanningToolCategory category) {
        PlanningToolCategory requiredCategory = Objects.requireNonNull(category,
                "candidate-bound planning tool category must not be null");
        if (requiredCategory != PlanningToolCategory.QUERY && requiredCategory != PlanningToolCategory.FOLLOW_UP_QUERY) {
            throw new IllegalArgumentException("candidate-bound planning tools must be QUERY categories");
        }
    }

    /**
     * 保留既有 mapper registration 的 capability 與 provider payload 投影行為
     */
    private static final class MapperQueryPlanningStrategy<P, E> implements QueryPlanningStrategy<P, E> {

        private final CapabilityPolicy policy;
        private final QueryPlanningMapper<P, E> mapper;
        private final CanonicalCapabilityPayloadCodec payloadCodec;

        private MapperQueryPlanningStrategy(
                CapabilityPolicy policy,
                QueryPlanningMapper<P, E> mapper,
                CanonicalCapabilityPayloadCodec payloadCodec) {
            this.policy = Objects.requireNonNull(policy, "planning registration policy must not be null");
            this.mapper = Objects.requireNonNull(mapper, "planning mapper must not be null");
            this.payloadCodec = Objects.requireNonNull(payloadCodec, "capability payload codec must not be null");
        }

        @Override
        public boolean isIssued(AgentPromptContext context) {
            Objects.requireNonNull(context, "agent prompt context must not be null");
            return context.issuedCapabilities().containsValue(policy);
        }

        @Override
        public AgentAction toAction(P input, AgentPromptContext context) {
            Objects.requireNonNull(context, "agent prompt context must not be null");
            CapabilityHandle capability = context.issuedCapabilities().entrySet().stream()
                    .filter(entry -> entry.getValue().equals(policy))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("planning tool was not issued"));
            QueryPlanningSelection<E> selection = mapper.map(input);
            CapabilityInputPayload payload = ProviderBoundFollowUp.boundPayload(context, capability, policy,
                            selection.candidateReferences())
                    .orElseGet(() -> payloadCodec.encode(selection.executionInput()));
            return new QueryAction(capability, selection.candidateReferences(), selection.questionToResolve(), payload,
                    selection.rationale());
        }
    }

}
