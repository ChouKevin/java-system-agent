package com.java.system.agent.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.capability.planning.PlanningToolSchemaFactory;
import com.java.system.agent.codebase.planning.EntryPointType;
import com.java.system.agent.codebase.planning.IncomingCallGraphPlanningInput;
import com.java.system.agent.codebase.planning.ListEntryPointsPlanningInput;
import com.java.system.agent.codebase.planning.LookupApiRoutePlanningInput;
import com.java.system.agent.codebase.planning.OutgoingCallGraphPlanningInput;
import com.java.system.agent.codebase.planning.SuggestApiRoutePlanningInput;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void generates_required_nonblank_candidate_handles_for_every_registered_input_type() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PlanningToolSchemaFactory factory = new PlanningToolSchemaFactory(mapper);

        for (Class<?> inputType : registeredInputTypes()) {
            JsonNode root = mapper.readTree(factory.schemaFor(inputType));

            assertThat(root.path("required")).extracting(JsonNode::asText).contains("candidateHandles");
            assertThat(root.path("properties").path("candidateHandles").path("items").path("minLength").asInt())
                    .isGreaterThanOrEqualTo(1);
        }
    }

    private static List<Class<?>> registeredInputTypes() {
        return List.of(
                ListEntryPointsPlanningInput.class,
                LookupApiRoutePlanningInput.class,
                SuggestApiRoutePlanningInput.class,
                OutgoingCallGraphPlanningInput.class,
                IncomingCallGraphPlanningInput.class);
    }
}
