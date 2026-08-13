package com.java.system.agent.model.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.capability.planning.PlanQuestionPlanningInput;
import com.java.system.agent.capability.planning.SubmitAnswerPlanningInput;
import com.java.system.agent.codeintelligence.planning.DiscoverConceptsPlanningInput;
import com.java.system.agent.codeintelligence.planning.DiscoverTypeMembersPlanningInput;
import com.java.system.agent.codeintelligence.planning.EntryPointType;
import com.java.system.agent.codeintelligence.planning.IncomingCallGraphPlanningInput;
import com.java.system.agent.codeintelligence.planning.ListEntryPointsPlanningInput;
import com.java.system.agent.codeintelligence.planning.LookupApiRoutePlanningInput;
import com.java.system.agent.codeintelligence.planning.OutgoingCallGraphPlanningInput;
import com.java.system.agent.codeintelligence.planning.SuggestApiRoutePlanningInput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QUERY planning schema 由 input type 產生且封閉物件屬性測試
 */
class SpringAiPlanningToolSchemaFactoryTest {

    @Test
    void generates_a_closed_schema_from_the_planning_input_type() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String schema = new SpringAiPlanningToolSchemaFactory().createSchema(ListEntryPointsPlanningInput.class);
        JsonNode root = mapper.readTree(schema);

        assertThat(root.path("additionalProperties").asBoolean()).isFalse();
        assertThat(root.path("properties").path("type").path("enum")).extracting(jsonNode -> jsonNode.asText())
                .contains(EntryPointType.API.name());
        assertThat(root.path("required")).extracting(jsonNode -> jsonNode.asText())
                .contains("candidateHandles", "questionToResolve", "rationale");
        assertThat(root.path("properties").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("candidateHandles", "questionToResolve", "rationale", "type");
        assertThat(root.at("/properties/additionalProperties").isMissingNode()).isTrue();
        assertThat(root.at("/$defs/additionalProperties").isMissingNode()).isTrue();
    }

    @Test
    void generates_required_nonblank_candidate_handles_for_every_registered_input_type() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SpringAiPlanningToolSchemaFactory factory = new SpringAiPlanningToolSchemaFactory();

        for (Class<?> inputType : queryInputTypes()) {
            JsonNode root = mapper.readTree(factory.createSchema(inputType));

            assertThat(root.path("required")).extracting(jsonNode -> jsonNode.asText()).contains("candidateHandles");
            assertThat(root.path("properties").path("candidateHandles").path("items").path("minLength").asInt())
                    .isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void generates_closed_nested_answer_statement_constraints_from_the_record_contract() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode schema = mapper.readTree(new SpringAiPlanningToolSchemaFactory().createSchema(SubmitAnswerPlanningInput.class));
        JsonNode statement = schema.path("properties").path("statements").path("items");
        JsonNode resolution = schema.path("properties").path("resolutions").path("items");

        assertThat(schema.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("statements", "resolutions");
        assertThat(schema.path("properties").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("statements", "resolutions");
        assertThat(schema.path("properties").path("statements").path("minItems").asInt()).isEqualTo(1);
        assertThat(schema.path("properties").path("resolutions").path("minItems").asInt()).isEqualTo(1);
        assertThat(statement.path("additionalProperties").asBoolean()).isFalse();
        assertThat(statement.path("required")).extracting(jsonNode -> jsonNode.asText())
                .containsExactlyInAnyOrder("statementId", "type", "text", "citationHandles", "observationIds");
        assertThat(statement.path("properties").path("statementId").path("minLength").asInt()).isEqualTo(1);
        assertThat(statement.at("/properties/statementId/pattern").isMissingNode()).isTrue();
        assertThat(statement.path("properties").path("text").path("minLength").asInt()).isEqualTo(1);
        assertThat(statement.path("properties").path("citationHandles").path("items").path("minLength").asInt())
                .isEqualTo(1);
        assertThat(statement.path("properties").path("observationIds").path("type").asText()).isEqualTo("array");
        assertThat(statement.path("properties").path("observationIds").path("items").path("type").asText())
                .isEqualTo("string");
        assertThat(statement.path("properties").path("observationIds").path("items").path("minLength").asInt())
                .isEqualTo(1);
        assertThat(statement.path("properties").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("statementId", "type", "text", "claimId", "citationHandles", "observationIds");
        assertThat(statement.at("/properties/additionalProperties").isMissingNode()).isTrue();
        assertThat(resolution.path("additionalProperties").asBoolean()).isFalse();
        assertThat(resolution.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("needId", "status", "evidenceHandles", "observationIds");
        assertThat(resolution.path("properties").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("needId", "status", "evidenceHandles", "observationIds");
        assertThat(resolution.path("properties").path("needId").path("minLength").asInt()).isEqualTo(1);
        assertThat(resolution.path("properties").path("needId").path("maxLength").asInt()).isEqualTo(32);
        assertThat(resolution.path("properties").path("status").path("type").asText()).isEqualTo("string");
        assertThat(resolution.path("properties").path("evidenceHandles").path("items").path("minLength").asInt())
                .isEqualTo(1);
        assertThat(resolution.path("properties").path("observationIds").path("items").path("minLength").asInt())
                .isEqualTo(1);
    }

    @Test
    void generates_only_ordered_information_need_fields_for_question_planning() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode schema = mapper.readTree(new SpringAiPlanningToolSchemaFactory().createSchema(PlanQuestionPlanningInput.class));
        JsonNode needs = schema.path("properties").path("needs");
        JsonNode informationNeed = needs.path("items");

        assertThat(schema.path("properties").fieldNames()).toIterable().containsExactly("needs");
        assertThat(schema.path("required")).extracting(JsonNode::asText).containsExactly("needs");
        assertThat(needs.path("minItems").asInt()).isEqualTo(1);
        assertThat(needs.path("maxItems").asInt()).isEqualTo(12);
        assertThat(informationNeed.path("additionalProperties").asBoolean()).isFalse();
        assertThat(informationNeed.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("id", "description");
        assertThat(informationNeed.path("properties").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "description");
        assertThat(informationNeed.path("properties").path("id").path("minLength").asInt()).isEqualTo(1);
        assertThat(informationNeed.at("/properties/id/pattern").isMissingNode()).isTrue();
        assertThat(informationNeed.path("properties").path("id").path("maxLength").asInt()).isEqualTo(32);
        assertThat(informationNeed.path("properties").path("description").path("minLength").asInt()).isEqualTo(1);
        assertThat(informationNeed.at("/properties/description/pattern").isMissingNode()).isTrue();
        assertThat(informationNeed.path("properties").path("description").path("maxLength").asInt()).isEqualTo(500);
    }

    @Test
    void exposes_discover_concepts_allowed_values_to_the_model() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode schema = mapper.readTree(
                new SpringAiPlanningToolSchemaFactory().createSchema(DiscoverConceptsPlanningInput.class));

        JsonNode properties = schema.path("properties");
        JsonNode searchCriteria = properties.path("searchCriteria");
        JsonNode criteriaProperties = searchCriteria.path("properties");
        assertThat(schema.path("required")).extracting(JsonNode::asText)
                .doesNotContain("searchCriteria", "limit");
        assertThat(criteriaProperties.path("terms").path("items").path("properties")
                .path("matchMode").path("enum"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("TOKEN_EXACT", "TOKEN_PREFIX");
        assertThat(criteriaProperties.path("terms").path("items").path("properties")
                .path("value").path("minLength").asInt()).isEqualTo(2);
        assertThat(criteriaProperties.path("kinds").path("items").path("enum"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(
                        "TYPE", "METHOD", "FIELD", "ANNOTATION_USAGE", "TYPE_USAGE", "API_ROUTE",
                        "MQ_DESTINATION", "SCHEDULE", "MAPPER_STATEMENT", "SQL_IDENTIFIER",
                        "CONFIGURATION_KEY", "OUTBOUND_API", "MQ_PUBLISHER", "ERROR_CONTRACT", "ENUM_CONSTANT");
        assertThat(criteriaProperties.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("terms", "kinds", "packagePrefix");
        assertThat(properties.path("limit").path("minimum").asInt()).isEqualTo(1);
        assertThat(properties.path("limit").path("maximum").asInt()).isEqualTo(100);
        assertThat(properties.path("offset").isMissingNode()).isTrue();
    }

    @Test
    void projects_optional_nested_constraints_to_the_model_schema() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode schema = mapper.readTree(
                new SpringAiPlanningToolSchemaFactory().createSchema(DiscoverTypeMembersPlanningInput.class));
        JsonNode initialFilter = schema.path("properties").path("initialFilter");
        JsonNode memberKinds = initialFilter.path("properties").path("memberKinds");
        JsonNode namePrefix = initialFilter.path("properties").path("namePrefix");

        assertThat(schema.path("required")).extracting(JsonNode::asText).doesNotContain("initialFilter");
        assertThat(initialFilter.path("additionalProperties").asBoolean()).isFalse();
        assertThat(initialFilter.path("type")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("object", "null");
        assertThat(initialFilter.path("required")).extracting(JsonNode::asText).doesNotContain("namePrefix");
        assertThat(memberKinds.path("minItems").asInt()).isEqualTo(1);
        assertThat(memberKinds.path("items").path("enum")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("METHOD", "FIELD", "ENUM_CONSTANT", "RECORD_COMPONENT");
        assertThat(namePrefix.path("minLength").asInt()).isEqualTo(1);
        assertThat(initialFilter.at("/properties/namePrefix/pattern").isMissingNode()).isTrue();
        assertThat(namePrefix.path("type")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("string", "null");
    }

    private static List<Class<?>> queryInputTypes() {
        return List.of(
                ListEntryPointsPlanningInput.class,
                LookupApiRoutePlanningInput.class,
                SuggestApiRoutePlanningInput.class,
                OutgoingCallGraphPlanningInput.class,
                IncomingCallGraphPlanningInput.class);
    }

}
