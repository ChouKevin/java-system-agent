package com.java.system.agent.capability.planning;

import com.java.system.agent.capability.spi.CapabilityExecutionContext;
import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.runtime.domain.action.QueryAction;
import com.java.system.agent.runtime.domain.capability.CapabilityPolicy;
import com.java.system.agent.runtime.domain.handle.CapabilityHandle;
import com.java.system.agent.runtime.domain.handle.HandleBinding;
import com.java.system.agent.runtime.port.out.AgentActionProposal;
import com.java.system.agent.runtime.port.out.AgentActionContractException;
import com.java.system.agent.runtime.port.out.AgentPromptContext;
import com.java.system.agent.runtime.port.out.CapabilityCatalogPort;
import com.java.system.agent.runtime.port.out.CapabilityExecutionContractException;
import com.java.system.agent.runtime.port.out.CapabilityExecutionResult;
import com.java.system.agent.runtime.port.out.CapabilityInvocation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 唯一的 QUERY planning tool 名稱 catalog、嚴格解碼與 registration 型別委派入口
 */
public final class PlanningToolRegistry implements CapabilityCatalogPort {

    private static final Pattern TOOL_NAME = Pattern.compile("[a-z][a-z0-9_]*");

    private final Map<CapabilityIdentity, QueryPlanningToolRegistration<?, ?>> registrations;
    private final StrictPlanningToolDecoder decoder;
    private final CanonicalCapabilityPayloadCodec payloadCodec;

    public PlanningToolRegistry(
            List<QueryPlanningToolRegistration<?, ?>> registrations,
            StrictPlanningToolDecoder decoder,
            CanonicalCapabilityPayloadCodec payloadCodec,
            PlanningToolSchemaFactory schemaFactory) {
        this.registrations = index(registrations, Objects.requireNonNull(
                schemaFactory, "planning schema factory must not be null"));
        this.decoder = Objects.requireNonNull(decoder, "planning tool decoder must not be null");
        this.payloadCodec = Objects.requireNonNull(payloadCodec, "capability payload codec must not be null");
    }

    @Override
    public List<CapabilityPolicy> availableCapabilities() {
        return registrations.values().stream().map(QueryPlanningToolRegistration::policy).toList();
    }

    public List<ToolCallback> issuedCallbacks(AgentPromptContext context) {
        Objects.requireNonNull(context, "agent prompt context must not be null");
        return context.issuedCapabilities().values().stream()
                .map(this::registration)
                .map(QueryPlanningToolRegistration::callback)
                .toList();
    }

    public AgentActionProposal interpretToolCall(AssistantMessage.ToolCall toolCall, AgentPromptContext context) {
        try {
            Objects.requireNonNull(toolCall, "tool call must not be null");
            Objects.requireNonNull(context, "agent prompt context must not be null");
            QueryPlanningToolRegistration<?, ?> registration = registration(toolCall.name());
            CapabilityHandle capability = issuedCapability(registration.policy(), context);
            return new AgentActionProposal.Proposed(interpret(registration, toolCall.arguments(), capability));
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
        return executeTyped(registration(invocation.capability()), invocation);
    }

    public static <P, E> QueryPlanningToolRegistration<P, E> registration(
            CapabilityPolicy policy,
            Class<P> planningInputType,
            Class<E> executionInputType,
            QueryPlanningMapper<P, E> mapper,
            CapabilityExecutor<E> executor,
            PlanningToolSchemaFactory schemaFactory) {
        String schema = schemaFactory.schemaFor(planningInputType);
        ToolCallback callback = new SchemaToolCallback(policy.name(), schema);
        return new QueryPlanningToolRegistration<>(policy, planningInputType, executionInputType, mapper, executor, callback);
    }

    private <P, E> QueryAction interpret(
            QueryPlanningToolRegistration<P, E> registration,
            String rawInput,
            CapabilityHandle capability) {
        P planningInput = decoder.decode(rawInput, registration.planningInputType());
        QueryPlanningSelection<E> selection = registration.mapper().map(planningInput);
        return new QueryAction(capability, selection.candidateReferences(), selection.questionToResolve(),
                payloadCodec.encode(selection.executionInput()), selection.rationale());
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

    private QueryPlanningToolRegistration<?, ?> registration(CapabilityPolicy policy) {
        return registration(new CapabilityIdentity(policy.name(), policy.version()));
    }

    private QueryPlanningToolRegistration<?, ?> registration(String name) {
        return registrations.values().stream().filter(registration -> registration.name().equals(name)).findFirst()
                .orElseThrow(() -> new PlanningToolInputException());
    }

    private QueryPlanningToolRegistration<?, ?> registration(CapabilityIdentity identity) {
        QueryPlanningToolRegistration<?, ?> registration = registrations.get(identity);
        if (Objects.isNull(registration)) {
            throw new CapabilityExecutionContractException("validated capability has no registered executor");
        }
        return registration;
    }

    private static CapabilityHandle issuedCapability(CapabilityPolicy policy, AgentPromptContext context) {
        return context.issuedCapabilities().entrySet().stream()
                .filter(entry -> entry.getValue().equals(policy))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new PlanningToolInputException());
    }

    private static Map<CapabilityIdentity, QueryPlanningToolRegistration<?, ?>> index(
            List<QueryPlanningToolRegistration<?, ?>> values,
            PlanningToolSchemaFactory schemaFactory) {
        Objects.requireNonNull(values, "planning registrations must not be null");
        Map<CapabilityIdentity, QueryPlanningToolRegistration<?, ?>> result = new LinkedHashMap<>();
        java.util.Set<String> names = new HashSet<>();
        for (QueryPlanningToolRegistration<?, ?> registration : values) {
            QueryPlanningToolRegistration<?, ?> required = Objects.requireNonNull(
                    registration, "planning registration must not contain null");
            schemaFactory.verifyRegisteredSchema(required.planningInputType(),
                    required.callback().getToolDefinition().inputSchema());
            if (!TOOL_NAME.matcher(required.name()).matches()) {
                throw new IllegalArgumentException("planning tool name must be canonical lowercase underscore text");
            }
            if (!names.add(required.name())) {
                throw new IllegalArgumentException("planning registrations must have unique capability names");
            }
            CapabilityIdentity identity = new CapabilityIdentity(required.policy().name(), required.policy().version());
            if (Objects.nonNull(result.putIfAbsent(identity, required))) {
                throw new IllegalArgumentException("planning registrations must have unique capability name and version");
            }
        }
        return Map.copyOf(result);
    }

    private record CapabilityIdentity(String name, String version) {
    }

    private record SchemaToolCallback(ToolDefinition definition) implements ToolCallback {

        SchemaToolCallback(String name, String schema) {
            this(DefaultToolDefinition.builder().name(name).description("Agent QUERY capability").inputSchema(schema).build());
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            return toolInput;
        }
    }
}
