package com.java.system.agent.runtime.domain.capability;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Capability 一個具名查詢參數的契約
 */
public record ArgumentDefinition(
        String name,
        ArgumentType type,
        boolean required,
        Integer minimum,
        Integer maximum,
        Set<String> enumValues) {

    public ArgumentDefinition {
        Objects.requireNonNull(name, "argument name must not be null");
        Objects.requireNonNull(type, "argument type must not be null");
        Objects.requireNonNull(enumValues, "argument enum values must not be null");
        name = name.trim();
        if (name.isBlank()) {
            throw new IllegalArgumentException("argument name must not be blank");
        }
        if ((Objects.nonNull(minimum) || Objects.nonNull(maximum)) && type != ArgumentType.INTEGER) {
            throw new IllegalArgumentException("only integer arguments may declare ranges");
        }
        if (Objects.nonNull(minimum) && Objects.nonNull(maximum) && minimum > maximum) {
            throw new IllegalArgumentException("argument minimum must not exceed maximum");
        }
        LinkedHashSet<String> copiedValues = new LinkedHashSet<>();
        for (String enumValue : enumValues) {
            Objects.requireNonNull(enumValue, "argument enum value must not be null");
            String normalizedValue = enumValue.trim();
            if (normalizedValue.isBlank()) {
                throw new IllegalArgumentException("argument enum value must not be blank");
            }
            copiedValues.add(normalizedValue);
        }
        enumValues = Set.copyOf(copiedValues);
        if (type == ArgumentType.ENUM && enumValues.isEmpty()) {
            throw new IllegalArgumentException("enum arguments require allowed values");
        }
        if (type != ArgumentType.ENUM && !enumValues.isEmpty()) {
            throw new IllegalArgumentException("only enum arguments may declare allowed values");
        }
    }
}
