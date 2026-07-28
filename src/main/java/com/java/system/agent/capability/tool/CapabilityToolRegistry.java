package com.java.system.agent.capability.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.runtime.domain.action.QueryAction;
import com.java.system.agent.runtime.domain.capability.CapabilityPolicy;
import com.java.system.agent.runtime.domain.candidate.CandidateKind;
import com.java.system.agent.runtime.domain.handle.CapabilityHandle;
import com.java.system.agent.runtime.domain.handle.CandidateHandle;
import com.java.system.agent.runtime.domain.handle.HandleBinding;
import com.java.system.agent.runtime.port.out.AgentActionProposal;
import com.java.system.agent.runtime.port.out.AgentPromptContext;
import com.java.system.agent.runtime.port.out.CapabilityCatalogPort;
import com.java.system.agent.runtime.port.out.CapabilityExecutionContractException;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 將 runtime policy、Spring AI tool 與執行器綁定成唯一 capability 登錄來源
 */
public final class CapabilityToolRegistry implements CapabilityCatalogPort {

    public static final String INVALID_TOOL_INPUT = "INVALID_TOOL_INPUT";

    private final List<Registration> registrations;
    private final Map<CapabilityPolicy, Registration> registrationsByPolicy;
    private final ObjectMapper objectMapper;

    public CapabilityToolRegistry(List<Registration> registrations) {
        this(registrations, new ObjectMapper());
    }

    CapabilityToolRegistry(List<Registration> registrations, ObjectMapper objectMapper) {
        Objects.requireNonNull(registrations, "tool registrations must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        LinkedHashMap<CapabilityPolicy, Registration> indexed = new LinkedHashMap<>();
        for (Registration registration : registrations) {
            Registration requiredRegistration = Objects.requireNonNull(registration, "tool registration must not be null");
            if (Objects.nonNull(indexed.putIfAbsent(requiredRegistration.policy(), requiredRegistration))) {
                throw new IllegalArgumentException("duplicate capability tool policy");
            }
        }
        this.registrations = List.copyOf(indexed.values());
        this.registrationsByPolicy = Collections.unmodifiableMap(indexed);
    }

    @Override
    public List<CapabilityPolicy> availableCapabilities() {
        return registrations.stream().map(Registration::policy).toList();
    }

    public List<ToolCallback> issuedCallbacks(AgentPromptContext context) {
        Objects.requireNonNull(context, "agent prompt context must not be null");
        List<ToolCallback> callbacks = new ArrayList<>();
        for (CapabilityPolicy policy : context.issuedCapabilities().values()) {
            Registration registration = registrationsByPolicy.get(policy);
            if (Objects.nonNull(registration)) {
                callbacks.add(registration.callback());
            }
        }
        return List.copyOf(callbacks);
    }

    public AgentActionProposal interpretToolCall(AssistantMessage.ToolCall toolCall, AgentPromptContext context) {
        try {
            Objects.requireNonNull(toolCall, "tool call must not be null");
            Objects.requireNonNull(context, "agent prompt context must not be null");
            Registration registration = registration(toolCall.name(), context);
            DecodedToolInput input = registration.decoder().decode(toolCall.arguments(), objectMapper);
            CapabilityHandle capability = issuedCapability(registration.policy(), context);
            List<CandidateHandle> candidates = candidateHandles(input.candidateHandles(), context, binding(context));
            return new AgentActionProposal.Proposed(new QueryAction(
                    capability, candidates, input.questionToResolve(), input.arguments(), input.rationale()));
        } catch (ToolInputException exception) {
            return new AgentActionProposal.Malformed(INVALID_TOOL_INPUT);
        } catch (RuntimeException exception) {
            return new AgentActionProposal.Malformed("MALFORMED_ACTION_RESPONSE");
        }
    }

    public CapabilityExecutor executor(CapabilityPolicy policy) {
        Registration registration = registrationsByPolicy.get(Objects.requireNonNull(policy, "capability policy must not be null"));
        if (Objects.isNull(registration)) {
            throw new CapabilityExecutionContractException("validated capability has no registered executor");
        }
        return registration.executor();
    }

    public static Registration registration(CapabilityPolicy policy, ToolInputDecoder decoder, CapabilityExecutor executor) {
        return new Registration(policy, decoder, executor, new PlanningToolCallback(policy, decoder.inputSchema()));
    }

    private Registration registration(String toolName, AgentPromptContext context) {
        List<Registration> matching = registrations.stream()
                .filter(registration -> registration.callback().getToolDefinition().name().equals(toolName))
                .filter(registration -> hasIssuedPolicy(registration.policy(), context))
                .toList();
        if (matching.size() != 1) {
            throw new IllegalArgumentException("tool call does not resolve to one issued capability");
        }
        return matching.getFirst();
    }

    private static boolean hasIssuedPolicy(CapabilityPolicy policy, AgentPromptContext context) {
        return context.issuedCapabilities().values().stream()
                .filter(issued -> issued.name().equals(policy.name()) && issued.version().equals(policy.version()))
                .count() == 1;
    }

    private static CapabilityHandle issuedCapability(CapabilityPolicy policy, AgentPromptContext context) {
        List<CapabilityHandle> matching = context.issuedCapabilities().entrySet().stream()
                .filter(entry -> entry.getValue().name().equals(policy.name())
                        && entry.getValue().version().equals(policy.version()))
                .map(Map.Entry::getKey)
                .toList();
        if (matching.size() != 1) {
            throw new IllegalArgumentException("tool call does not resolve to one issued capability handle");
        }
        return matching.getFirst();
    }

    private static List<CandidateHandle> candidateHandles(
            List<String> values, AgentPromptContext context, HandleBinding binding) {
        List<CandidateHandle> handles = new ArrayList<>();
        for (String value : values) {
            CandidateHandle handle = context.issuedCandidates().keySet().stream()
                    .filter(issued -> issued.value().equals(value))
                    .findFirst()
                    .orElseGet(() -> new CandidateHandle(value, binding, CandidateKind.REPOSITORY));
            handles.add(handle);
        }
        return List.copyOf(handles);
    }

    private static HandleBinding binding(AgentPromptContext context) {
        return context.issuedCapabilities().keySet().stream()
                .findFirst()
                .map(CapabilityHandle::binding)
                .orElseThrow(() -> new IllegalArgumentException("tool call requires an issued capability"));
    }

    /**
     * 一個 policy 對應的 planning callback、外部輸入解碼器與 read-only executor
     */
    public record Registration(CapabilityPolicy policy, ToolInputDecoder decoder, CapabilityExecutor executor,
                               ToolCallback callback) {
        public Registration {
            Objects.requireNonNull(policy, "capability policy must not be null");
            Objects.requireNonNull(decoder, "tool input decoder must not be null");
            Objects.requireNonNull(executor, "capability executor must not be null");
            Objects.requireNonNull(callback, "tool callback must not be null");
            if (!policy.equals(executor.capability())) {
                throw new IllegalArgumentException("capability executor must match registration policy");
            }
        }
    }

    /**
     * 將 provider JSON 嚴格解碼成 runtime 不解讀的引數 map
     */
    public interface ToolInputDecoder {
        DecodedToolInput decode(String json, ObjectMapper objectMapper);

        String inputSchema();
    }

    /**
     * 已通過 adapter 專屬輸入驗證的 QUERY payload
     */
    public record DecodedToolInput(List<String> candidateHandles, String questionToResolve,
                                   Map<String, String> arguments, String rationale) {
        public DecodedToolInput {
            candidateHandles = List.copyOf(Objects.requireNonNull(candidateHandles, "candidate handles must not be null"));
            arguments = Map.copyOf(Objects.requireNonNull(arguments, "arguments must not be null"));
            requireText(questionToResolve, "question to resolve");
            requireText(rationale, "rationale");
        }
    }

    /**
     * 五個內建工具共用的嚴格 JSON decoder 工廠
     */
    public static ToolInputDecoder decoder(ArgumentRule... rules) {
        List<ArgumentRule> copiedRules = List.of(rules);
        return new ToolInputDecoder() {
            @Override
            public DecodedToolInput decode(String json, ObjectMapper objectMapper) {
                try {
                    JsonNode root = objectMapper.readTree(requireText(json, "tool input"));
                    if (!root.isObject()) {
                        throw new ToolInputException();
                    }
                    Set<String> allowed = new LinkedHashSet<>(Set.of("candidateHandles", "questionToResolve", "rationale"));
                    copiedRules.forEach(rule -> allowed.add(rule.name()));
                    root.fieldNames().forEachRemaining(name -> {
                        if (!allowed.contains(name)) {
                            throw new ToolInputException();
                        }
                    });
                    List<String> candidates = candidates(root.path("candidateHandles"));
                    String question = text(root, "questionToResolve", true);
                    String rationale = text(root, "rationale", true);
                    LinkedHashMap<String, String> arguments = new LinkedHashMap<>();
                    for (ArgumentRule rule : copiedRules) {
                        rule.decode(root, arguments);
                    }
                    return new DecodedToolInput(candidates, question, arguments, rationale);
                } catch (ToolInputException exception) {
                    throw exception;
                } catch (Exception exception) {
                    throw new ToolInputException();
                }
            }

            @Override
            public String inputSchema() {
                StringBuilder properties = new StringBuilder("\"candidateHandles\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"questionToResolve\":{\"type\":\"string\"},\"rationale\":{\"type\":\"string\"}");
                for (ArgumentRule rule : copiedRules) {
                    properties.append(',').append(rule.schema());
                }
                return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{" + properties
                        + "},\"required\":[\"candidateHandles\",\"questionToResolve\",\"rationale\"]}";
            }
        };
    }

    public static ArgumentRule optionalEnum(String name, Set<String> values) { return new ArgumentRule(name, false, values, null, null); }

    public static ArgumentRule requiredText(String name) { return new ArgumentRule(name, true, Set.of(), null, null); }

    public static ArgumentRule optionalText(String name) { return new ArgumentRule(name, false, Set.of(), null, null); }

    public static ArgumentRule requiredInteger(String name, int minimum, int maximum) { return new ArgumentRule(name, true, Set.of(), minimum, maximum); }

    public static ArgumentRule optionalInteger(String name, int minimum, int maximum) { return new ArgumentRule(name, false, Set.of(), minimum, maximum); }

    /**
     * 一個 adapter 專屬 tool argument 的嚴格規則
     */
    public record ArgumentRule(String name, boolean required, Set<String> enumValues, Integer minimum, Integer maximum) {
        public ArgumentRule {
            requireText(name, "argument name");
            enumValues = Set.copyOf(Objects.requireNonNull(enumValues, "enum values must not be null"));
        }

        private void decode(JsonNode root, Map<String, String> arguments) {
            JsonNode value = root.get(name);
            if (Objects.isNull(value) || value.isNull()) {
                if (required) {
                    throw new ToolInputException();
                }
                return;
            }
            if (!enumValues.isEmpty()) {
                String parsed = textValue(value);
                if (!enumValues.contains(parsed)) {
                    throw new ToolInputException();
                }
                arguments.put(name, parsed);
                return;
            }
            if (Objects.nonNull(minimum)) {
                if (!value.isIntegralNumber() || value.intValue() < minimum || value.intValue() > maximum) {
                    throw new ToolInputException();
                }
                arguments.put(name, Integer.toString(value.intValue()));
                return;
            }
            arguments.put(name, textValue(value));
        }

        private String schema() {
            if (!enumValues.isEmpty()) {
                return "\"" + name + "\":{\"type\":\"string\",\"enum\":["
                        + enumValues.stream().sorted().map(value -> "\"" + value + "\"").collect(Collectors.joining(",")) + "]}";
            }
            if (Objects.nonNull(minimum)) {
                return "\"" + name + "\":{\"type\":\"integer\",\"minimum\":" + minimum
                        + ",\"maximum\":" + maximum + "}";
            }
            return "\"" + name + "\":{\"type\":\"string\"}";
        }
    }

    private static List<String> candidates(JsonNode node) {
        if (!node.isArray()) {
            throw new ToolInputException();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            values.add(textValue(value));
        }
        return List.copyOf(values);
    }

    private static String text(JsonNode root, String field, boolean required) {
        JsonNode node = root.get(field);
        if (Objects.isNull(node) || node.isNull()) {
            if (required) {
                throw new ToolInputException();
            }
            return "";
        }
        return textValue(node);
    }

    private static String textValue(JsonNode node) {
        if (!node.isTextual()) {
            throw new ToolInputException();
        }
        return requireText(node.textValue(), "tool text");
    }

    private static String requireText(String value, String description) {
        if (Objects.isNull(value) || value.isBlank()) {
            throw new ToolInputException();
        }
        return value;
    }

    private static final class PlanningToolCallback implements ToolCallback {
        private final ToolDefinition definition;

        private PlanningToolCallback(CapabilityPolicy policy, String inputSchema) {
            this.definition = DefaultToolDefinition.builder()
                    .name(policy.name())
                    .description("Propose a read-only query for " + policy.name())
                    .inputSchema(inputSchema)
                    .build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            return "TOOL_EXECUTION_DISABLED";
        }
    }

    private static final class ToolInputException extends IllegalArgumentException {
        private ToolInputException() {
            super("invalid tool input");
        }
    }
}
