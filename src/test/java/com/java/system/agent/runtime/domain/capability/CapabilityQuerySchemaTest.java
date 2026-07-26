package com.java.system.agent.runtime.domain.capability;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityQuerySchemaTest {

    @Test
    void should_accept_required_typed_and_bounded_arguments() {
        CapabilityQuerySchema schema = new CapabilityQuerySchema(List.of(
                new ArgumentDefinition("depth", ArgumentType.INTEGER, true, 1, 3, Set.of()),
                new ArgumentDefinition("includeExternal", ArgumentType.BOOLEAN, false, null, null, Set.of()),
                new ArgumentDefinition("format", ArgumentType.ENUM, true, null, null, Set.of("TREE", "TEXT"))));

        schema.validate(Map.of("depth", "2", "includeExternal", "true", "format", "TREE"));

    }

    @Test
    void should_identify_missing_unknown_range_and_enum_argument_contract_failures() {
        CapabilityQuerySchema schema = new CapabilityQuerySchema(List.of(
                new ArgumentDefinition("depth", ArgumentType.INTEGER, true, 1, 3, Set.of()),
                new ArgumentDefinition("format", ArgumentType.ENUM, true, null, null, Set.of("TREE", "TEXT"))));

        assertContractFailure(schema, Map.of("format", "TREE"), "depth");
        assertContractFailure(schema, Map.of("depth", "2", "format", "TREE", "unknown", "value"), "unknown");
        assertContractFailure(schema, Map.of("depth", "4", "format", "TREE"), "depth");
        assertContractFailure(schema, Map.of("depth", "2", "format", "JSON"), "format");
    }

    @Test
    void should_identify_blank_text_non_integer_and_invalid_boolean_contract_failures() {
        CapabilityQuerySchema schema = new CapabilityQuerySchema(List.of(
                new ArgumentDefinition("query", ArgumentType.TEXT, true, null, null, Set.of()),
                new ArgumentDefinition("depth", ArgumentType.INTEGER, true, 1, 3, Set.of()),
                new ArgumentDefinition("includeExternal", ArgumentType.BOOLEAN, true, null, null, Set.of())));

        assertContractFailure(schema, Map.of("query", " ", "depth", "2", "includeExternal", "true"), "query");
        assertContractFailure(schema, Map.of("query", "orders", "depth", "two", "includeExternal", "true"), "depth");
        assertContractFailure(schema, Map.of("query", "orders", "depth", "2", "includeExternal", "yes"),
                "includeExternal");
    }

    private static void assertContractFailure(CapabilityQuerySchema schema, Map<String, String> values, String argumentName) {
        assertThatThrownBy(() -> schema.validate(values))
                .isInstanceOf(CapabilityQueryContractException.class)
                .hasMessageContaining(argumentName);
    }
}
