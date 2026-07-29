package com.java.system.agent.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.capability.planning.PlanningToolSchemaFactory;
import com.java.system.agent.codebase.planning.EntryPointType;
import com.java.system.agent.codebase.planning.ListEntryPointsPlanningInput;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QUERY planning schema 由 input type 產生且封閉物件屬性測試
 */
class PlanningToolSchemaFactoryTest {

    @Test
    void generates_a_closed_schema_from_the_planning_input_type() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String schema = new PlanningToolSchemaFactory(mapper).schemaFor(ListEntryPointsPlanningInput.class);
        JsonNode root = mapper.readTree(schema);

        assertThat(root.path("additionalProperties").asBoolean()).isFalse();
        assertThat(root.path("properties").path("type").path("enum")).extracting(JsonNode::asText)
                .contains(EntryPointType.API.name());
        assertThat(root.path("required")).extracting(JsonNode::asText)
                .contains("candidateHandles", "questionToResolve", "rationale");
    }
}
