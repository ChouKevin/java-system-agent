package com.java.system.agent.capability.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;

import java.util.Objects;

/**
 * 從 planning input Java 型別產生唯一 provider schema 並封閉物件屬性
 */
public final class PlanningToolSchemaFactory {

    private final ObjectMapper mapper;

    public PlanningToolSchemaFactory(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "planning schema mapper must not be null");
    }

    public String schemaFor(Class<?> inputType) {
        Objects.requireNonNull(inputType, "planning schema input type must not be null");
        try {
            JsonNode root = mapper.readTree(JsonSchemaGenerator.generateForType(inputType));
            closeObjects(root);
            return mapper.writeValueAsString(root);
        } catch (Exception exception) {
            throw new IllegalArgumentException("planning input type cannot produce a closed schema", exception);
        }
    }

    private static void closeObjects(JsonNode node) {
        if (node.isObject()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("additionalProperties", false);
        }
        node.forEach(PlanningToolSchemaFactory::closeObjects);
    }
}
