package com.java.system.agent.capability.planning;

import com.java.system.agent.capability.spi.CapabilityExecutionContext;
import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.port.out.AgentActionProposal;
import com.java.system.agent.answering.port.out.AgentActionContractException;
import com.java.system.agent.answering.port.out.AgentPromptContext;
import com.java.system.agent.answering.port.out.CapabilityCatalogPort;
import com.java.system.agent.answering.port.out.CapabilityExecutionContractException;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.answering.port.out.CapabilityInvocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 唯一的 planning tool 名稱 catalog、嚴格解碼與 registration 型別委派入口
 */
public final class PlanningToolRegistry implements CapabilityCatalogPort {

    private static final Pattern TOOL_NAME = Pattern.compile("[a-z][a-z0-9_]*");

    private final List<PlanningToolRegistration<?>> registrations;
    private final Map<String, PlanningToolRegistration<?>> planningRegistrations;
    private final Map<CapabilityIdentity, QueryPlanningToolRegistration<?, ?>> queryRegistrations;
    private final StrictPlanningToolDecoder decoder;
    private final CanonicalCapabilityPayloadCodec payloadCodec;

    public PlanningToolRegistry(
            List<PlanningToolProvider> providers,
            StrictPlanningToolDecoder decoder,
            CanonicalCapabilityPayloadCodec payloadCodec) {
        RegistrationIndex index = index(providers);
        this.registrations = index.registrations();
        this.planningRegistrations = index.planningRegistrations();
        this.queryRegistrations = index.queryRegistrations();
        this.decoder = Objects.requireNonNull(decoder, "planning tool decoder must not be null");
        this.payloadCodec = Objects.requireNonNull(payloadCodec, "capability payload codec must not be null");
    }

    @Override
    public List<CapabilityPolicy> availableCapabilities() {
        return queryRegistrations.values().stream().map(queryRegistration -> queryRegistration.policy()).toList();
    }

    /**
     * 回傳依 provider-facing 名稱排序的不可變 planning registration
     */
    public List<PlanningToolRegistration<?>> registrations() {
        return registrations;
    }

    public List<PlanningToolRegistration<?>> issuedRegistrations(AgentPromptContext context) {
        Objects.requireNonNull(context, "agent prompt context must not be null");
        return registrations.stream()
                .filter(registration -> registration.isIssued(context))
                .toList();
    }

    public AgentActionProposal interpretToolCall(String toolName, String rawArguments, AgentPromptContext context) {
        try {
            Objects.requireNonNull(toolName, "tool name must not be null");
            Objects.requireNonNull(context, "agent prompt context must not be null");
            PlanningToolRegistration<?> registration = planningRegistrations.get(toolName);
            if (Objects.isNull(registration) || !registration.isIssued(context)) {
                return new AgentActionProposal.Malformed("MALFORMED_ACTION_RESPONSE");
            }
            return new AgentActionProposal.Proposed(interpret(registration, rawArguments, context));
        } catch (PlanningToolInputException exception) {
            return new AgentActionProposal.Malformed("INVALID_TOOL_INPUT");
        } catch (AgentActionContractException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AgentActionContractException("planning tool registry contract failed", exception);
        }
    }

    public CapabilityExecutionResult execute(CapabilityInvocation invocation) {
        Objects.requireNonNull(invocation, "capability invocation must not be null");
        return executeTyped(queryRegistration(invocation.capability()), invocation);
    }

    public static <P, E> QueryPlanningToolRegistration<P, E> registration(
            CapabilityPolicy policy,
            Class<P> planningInputType,
            Class<E> executionInputType,
            QueryPlanningMapper<P, E> mapper,
            CapabilityExecutor<E> executor,
            CanonicalCapabilityPayloadCodec payloadCodec) {
        return new QueryPlanningToolRegistration<>(
                policy, planningInputType, executionInputType, mapper, executor, payloadCodec);
    }

    private <I> AgentAction interpret(
            PlanningToolRegistration<I> registration,
            String rawInput,
            AgentPromptContext context) {
        I planningInput = decoder.decode(rawInput, registration.planningInputType());
        return registration.toAction(planningInput, context);
    }

    private <E> CapabilityExecutionResult executeTyped(
            QueryPlanningToolRegistration<?, E> registration,
            CapabilityInvocation invocation) {
        E input = payloadCodec.decode(invocation.payload(), registration.executionInputType());
        CapabilityExecutionContext context = new CapabilityExecutionContext(
                invocation.capability(), invocation.candidates(), invocation.question(), invocation.expectedRevisions());
        CapabilityExecutionResult result = registration.executor().execute(context, input);
        if (Objects.isNull(result)) {
            throw new CapabilityExecutionContractException("capability executor must return a result");
        }
        return result;
    }

    private QueryPlanningToolRegistration<?, ?> queryRegistration(CapabilityPolicy policy) {
        return queryRegistration(new CapabilityIdentity(policy.name(), policy.version()));
    }

    private QueryPlanningToolRegistration<?, ?> queryRegistration(CapabilityIdentity identity) {
        QueryPlanningToolRegistration<?, ?> registration = queryRegistrations.get(identity);
        if (Objects.isNull(registration)) {
            throw new CapabilityExecutionContractException("validated capability has no registered executor");
        }
        return registration;
    }

    private static RegistrationIndex index(List<PlanningToolProvider> providers) {
        Objects.requireNonNull(providers, "planning tool providers must not be null");
        List<PlanningToolRegistration<?>> values = registrations(providers);
        Map<String, PlanningToolRegistration<?>> planningRegistrations = new LinkedHashMap<>();
        Map<CapabilityIdentity, QueryPlanningToolRegistration<?, ?>> queryRegistrations = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for (PlanningToolRegistration<?> registration : values) {
            PlanningToolRegistration<?> required = Objects.requireNonNull(
                    registration, "planning registration must not contain null");
            if (required instanceof QueryPlanningToolRegistration<?, ?> queryRegistration) {
                CapabilityIdentity identity = new CapabilityIdentity(queryRegistration.policy().name(),
                        queryRegistration.policy().version());
                if (Objects.nonNull(queryRegistrations.putIfAbsent(identity, queryRegistration))) {
                    throw new IllegalArgumentException("duplicate QUERY identity in planning registrations");
                }
            }
        }
        for (PlanningToolRegistration<?> registration : values) {
            PlanningToolRegistration<?> required = Objects.requireNonNull(
                    registration, "planning registration must not contain null");
            if (!TOOL_NAME.matcher(required.name()).matches()) {
                throw new IllegalArgumentException("planning tool name must be canonical lowercase underscore text");
            }
            if (!names.add(required.name())) {
                throw new IllegalArgumentException("duplicate provider-facing name in planning registrations");
            }
            planningRegistrations.put(required.name(), required);
        }
        return new RegistrationIndex(values, Collections.unmodifiableMap(planningRegistrations),
                Collections.unmodifiableMap(queryRegistrations));
    }

    private static List<PlanningToolRegistration<?>> registrations(List<PlanningToolProvider> providers) {
        List<PlanningToolRegistration<?>> registrations = new ArrayList<>();
        for (PlanningToolProvider provider : providers) {
            PlanningToolProvider requiredProvider = Objects.requireNonNull(provider,
                    "planning tool providers must not contain null");
            List<PlanningToolRegistration<?>> providedRegistrations = Objects.requireNonNull(
                    requiredProvider.registrations(), "planning tool provider registrations must not be null");
            registrations.addAll(providedRegistrations);
        }
        registrations.sort(Comparator.comparing(planningToolRegistration -> planningToolRegistration.name()));
        return List.copyOf(registrations);
    }

    private record CapabilityIdentity(String name, String version) {
    }

    private record RegistrationIndex(
            List<PlanningToolRegistration<?>> registrations,
            Map<String, PlanningToolRegistration<?>> planningRegistrations,
            Map<CapabilityIdentity, QueryPlanningToolRegistration<?, ?>> queryRegistrations) {
    }

}
