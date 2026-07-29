package com.java.system.agent.model.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.java.system.agent.capability.planning.SubmitAnswerPlanningInput;
import com.java.system.agent.codeintelligence.planning.EntryPointType;
import com.java.system.agent.codeintelligence.planning.IncomingCallGraphPlanningInput;
import com.java.system.agent.codeintelligence.planning.ListEntryPointsPlanningInput;
import com.java.system.agent.codeintelligence.planning.LookupApiRoutePlanningInput;
import com.java.system.agent.codeintelligence.planning.OutgoingCallGraphPlanningInput;
import com.java.system.agent.codeintelligence.planning.SuggestApiRoutePlanningInput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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

        for (Class<?> inputType : registeredInputTypes()) {
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

        assertThat(statement.path("additionalProperties").asBoolean()).isFalse();
        assertThat(statement.path("required")).extracting(jsonNode -> jsonNode.asText())
                .containsExactlyInAnyOrder("statementId", "type", "text", "citationHandles", "observationIds");
        assertThat(statement.path("properties").path("statementId").path("minLength").asInt()).isEqualTo(1);
        assertThat(statement.path("properties").path("statementId").path("pattern").asText()).isEqualTo(".*\\S.*");
        assertThat(statement.path("properties").path("text").path("minLength").asInt()).isEqualTo(1);
        assertThat(statement.path("properties").path("citationHandles").path("items").path("minLength").asInt())
                .isEqualTo(1);
        assertThat(statement.path("properties").path("observationIds").path("items").path("minLength").asInt())
                .isEqualTo(1);
        assertThat(statement.path("properties").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("statementId", "type", "text", "claimId", "citationHandles", "observationIds");
        assertThat(statement.at("/properties/additionalProperties").isMissingNode()).isTrue();
    }

    @Test
    void rejects_every_registered_schema_that_differs_from_the_canonical_input_contract() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SpringAiPlanningToolSchemaFactory factory = new SpringAiPlanningToolSchemaFactory();

        for (TamperedSchema tampered : tamperedSchemas(factory, mapper)) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> factory.verifySchema(tampered.inputType(), mapper.writeValueAsString(tampered.schema())));
        }
    }

    @Test
    void accepts_the_schema_it_generates_for_every_registered_input_type() {
        ObjectMapper mapper = new ObjectMapper();
        SpringAiPlanningToolSchemaFactory factory = new SpringAiPlanningToolSchemaFactory();

        for (Class<?> inputType : registeredInputTypes()) {
            assertThatCode(() -> factory.verifySchema(inputType, factory.createSchema(inputType)))
                    .as(inputType.getSimpleName())
                    .doesNotThrowAnyException();
        }
    }

    private static List<TamperedSchema> tamperedSchemas(SpringAiPlanningToolSchemaFactory factory, ObjectMapper mapper) throws Exception {
        ObjectNode extraRequired = canonical(factory, mapper, ListEntryPointsPlanningInput.class);
        ((ArrayNode) extraRequired.path("required")).add("unexpected");

        ObjectNode extraProperty = canonical(factory, mapper, ListEntryPointsPlanningInput.class);
        ((ObjectNode) extraProperty.path("properties")).putObject("unexpected").put("type", "string");

        ObjectNode extraEnumValue = canonical(factory, mapper, ListEntryPointsPlanningInput.class);
        ((ArrayNode) extraEnumValue.path("properties").path("type").path("enum")).add("UNSUPPORTED");

        ObjectNode changedCollectionLimit = canonical(factory, mapper, ListEntryPointsPlanningInput.class);
        ((ObjectNode) changedCollectionLimit.path("properties").path("candidateHandles")).put("minItems", 0);

        ObjectNode changedRange = canonical(factory, mapper, SuggestApiRoutePlanningInput.class);
        ((ObjectNode) changedRange.path("properties").path("limit")).put("maximum", 21);

        ObjectNode explicitNullBranch = canonical(factory, mapper, ListEntryPointsPlanningInput.class);
        ObjectNode type = (ObjectNode) explicitNullBranch.path("properties").path("type");
        type.removeAll();
        type.putArray("anyOf").addObject().put("type", "string");
        type.withArray("anyOf").addObject().put("type", "null");

        ObjectNode changedNestedConstraint = canonical(factory, mapper, SubmitAnswerPlanningInput.class);
        ((ObjectNode) changedNestedConstraint.path("properties").path("statements").path("items")
                .path("properties").path("text")).put("minLength", 0);

        return List.of(
                new TamperedSchema(ListEntryPointsPlanningInput.class, extraRequired),
                new TamperedSchema(ListEntryPointsPlanningInput.class, extraProperty),
                new TamperedSchema(ListEntryPointsPlanningInput.class, extraEnumValue),
                new TamperedSchema(ListEntryPointsPlanningInput.class, changedCollectionLimit),
                new TamperedSchema(SuggestApiRoutePlanningInput.class, changedRange),
                new TamperedSchema(ListEntryPointsPlanningInput.class, explicitNullBranch),
                new TamperedSchema(SubmitAnswerPlanningInput.class, changedNestedConstraint));
    }

    private static ObjectNode canonical(SpringAiPlanningToolSchemaFactory factory, ObjectMapper mapper, Class<?> inputType) throws Exception {
        return (ObjectNode) mapper.readTree(factory.createSchema(inputType));
    }

    private static List<Class<?>> registeredInputTypes() {
        return List.of(
                ListEntryPointsPlanningInput.class,
                LookupApiRoutePlanningInput.class,
                SuggestApiRoutePlanningInput.class,
                OutgoingCallGraphPlanningInput.class,
                IncomingCallGraphPlanningInput.class);
    }

    /**
     * 模擬啟動驗證收到的非 canonical schema
     */
    private record TamperedSchema(Class<?> inputType, ObjectNode schema) {
    }
}
