package com.java.system.agent.runtime.domain.capability;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Capability 查詢引數的純契約檢查器
 */
public record CapabilityQuerySchema(List<ArgumentDefinition> arguments) {

    public CapabilityQuerySchema {
        Objects.requireNonNull(arguments, "capability query arguments must not be null");
        LinkedHashMap<String, ArgumentDefinition> definitions = new LinkedHashMap<>();
        for (ArgumentDefinition argument : arguments) {
            Objects.requireNonNull(argument, "capability argument definition must not be null");
            if (Objects.nonNull(definitions.putIfAbsent(argument.name(), argument))) {
                throw new IllegalArgumentException("capability argument names must be unique");
            }
        }
        arguments = List.copyOf(definitions.values());
    }

    public void validate(Map<String, String> values) {
        Objects.requireNonNull(values, "capability query values must not be null");
        Map<String, ArgumentDefinition> definitions = definitionsByName();
        for (Map.Entry<String, String> value : values.entrySet()) {
            String name = Objects.requireNonNull(value.getKey(), "capability query argument name must not be null");
            String rawValue = Objects.requireNonNull(value.getValue(), "capability query argument value must not be null");
            ArgumentDefinition definition = definitions.get(name);
            if (Objects.isNull(definition)) {
                throw new CapabilityQueryContractException("unknown capability query argument: " + name);
            }
            validateValue(definition, rawValue);
        }
        for (ArgumentDefinition definition : arguments) {
            if (definition.required() && !values.containsKey(definition.name())) {
                throw new CapabilityQueryContractException("missing required capability query argument: " + definition.name());
            }
        }
    }

    private Map<String, ArgumentDefinition> definitionsByName() {
        LinkedHashMap<String, ArgumentDefinition> definitions = new LinkedHashMap<>();
        for (ArgumentDefinition argument : arguments) {
            definitions.put(argument.name(), argument);
        }
        return Map.copyOf(definitions);
    }

    private static void validateValue(ArgumentDefinition definition, String rawValue) {
        switch (definition.type()) {
            case TEXT -> {
                if (rawValue.isBlank()) {
                    throw new CapabilityQueryContractException(
                            "text capability query argument must not be blank: " + definition.name());
                }
            }
            case INTEGER -> validateInteger(definition, rawValue);
            case BOOLEAN -> {
                if (!rawValue.equals("true") && !rawValue.equals("false")) {
                    throw new CapabilityQueryContractException(
                            "boolean capability query argument must be true or false: " + definition.name());
                }
            }
            case ENUM -> {
                if (!definition.enumValues().contains(rawValue)) {
                    throw new CapabilityQueryContractException(
                            "enum capability query argument has an unsupported value: " + definition.name());
                }
            }
        }
    }

    private static void validateInteger(ArgumentDefinition definition, String rawValue) {
        try {
            int value = Integer.parseInt(rawValue);
            if (Objects.nonNull(definition.minimum()) && value < definition.minimum()) {
                throw new CapabilityQueryContractException(
                        "integer capability query argument is below its minimum: " + definition.name());
            }
            if (Objects.nonNull(definition.maximum()) && value > definition.maximum()) {
                throw new CapabilityQueryContractException(
                        "integer capability query argument exceeds its maximum: " + definition.name());
            }
        } catch (NumberFormatException exception) {
            throw new CapabilityQueryContractException(
                    "integer capability query argument must be an integer: " + definition.name(), exception);
        }
    }
}
