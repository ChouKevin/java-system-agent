package com.java.system.agent.model.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.capability.planning.PlanQuestionPlanningInput;
import com.java.system.agent.capability.planning.SubmitAnswerPlanningInput;
import com.java.system.agent.codeintelligence.planning.DiscoverConceptsPlanningInput;
import com.java.system.agent.codeintelligence.planning.DiscoverEventListenersPlanningInput;
import com.java.system.agent.codeintelligence.planning.DiscoverMethodImplementationsPlanningInput;
import com.java.system.agent.codeintelligence.planning.DiscoverTypeMembersPlanningInput;
import com.java.system.agent.codeintelligence.planning.FindInternalReferencesPlanningInput;
import com.java.system.agent.codeintelligence.planning.GetEvidenceSourcePlanningInput;
import com.java.system.agent.codeintelligence.planning.GetMethodSourcePlanningInput;
import com.java.system.agent.codeintelligence.planning.GetSourceSegmentPlanningInput;
import com.java.system.agent.codeintelligence.planning.IncomingCallGraphPlanningInput;
import com.java.system.agent.codeintelligence.planning.ListEntryPointsPlanningInput;
import com.java.system.agent.codeintelligence.planning.LookupApiRoutePlanningInput;
import com.java.system.agent.codeintelligence.planning.OutgoingCallGraphPlanningInput;
import com.java.system.agent.codeintelligence.planning.ResolveConceptPlanningInput;
import com.java.system.agent.codeintelligence.planning.ResolveSourceSymbolPlanningInput;
import com.java.system.agent.codeintelligence.planning.SuggestApiRoutePlanningInput;
import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.schema.JsonSchemaConverter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SpringAiPlanningToolSchemaFactoryTest {

    @Test
    void creates_schemas_accepted_by_the_runtime_google_tool_converter() {
        SpringAiPlanningToolSchemaFactory factory = new SpringAiPlanningToolSchemaFactory();
        List<Class<?>> inputs = List.of(
                PlanQuestionPlanningInput.class, SubmitAnswerPlanningInput.class,
                ListEntryPointsPlanningInput.class, LookupApiRoutePlanningInput.class,
                SuggestApiRoutePlanningInput.class, OutgoingCallGraphPlanningInput.class,
                IncomingCallGraphPlanningInput.class, DiscoverConceptsPlanningInput.class,
                ResolveConceptPlanningInput.class, DiscoverEventListenersPlanningInput.class,
                DiscoverMethodImplementationsPlanningInput.class, DiscoverTypeMembersPlanningInput.class,
                FindInternalReferencesPlanningInput.class, GetEvidenceSourcePlanningInput.class,
                GetMethodSourcePlanningInput.class, GetSourceSegmentPlanningInput.class,
                ResolveSourceSymbolPlanningInput.class);

        for (Class<?> input : inputs) {
            assertThatCode(() -> JsonSchemaConverter.convertToOpenApiSchema(
                    JsonSchemaConverter.fromJson(factory.createSchema(input))))
                    .as(input.getSimpleName())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void exposes_candidate_free_exact_typed_query_schemas() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SpringAiPlanningToolSchemaFactory factory = new SpringAiPlanningToolSchemaFactory();
        List<Class<?>> inputs = List.of(
                ListEntryPointsPlanningInput.class, LookupApiRoutePlanningInput.class,
                SuggestApiRoutePlanningInput.class, OutgoingCallGraphPlanningInput.class,
                IncomingCallGraphPlanningInput.class, DiscoverConceptsPlanningInput.class,
                ResolveConceptPlanningInput.class, DiscoverEventListenersPlanningInput.class,
                DiscoverMethodImplementationsPlanningInput.class, DiscoverTypeMembersPlanningInput.class,
                FindInternalReferencesPlanningInput.class, GetEvidenceSourcePlanningInput.class,
                GetMethodSourcePlanningInput.class, GetSourceSegmentPlanningInput.class,
                ResolveSourceSymbolPlanningInput.class);
        for (Class<?> input : inputs) {
            JsonNode schema = mapper.readTree(factory.createSchema(input));
            assertThat(schema.path("properties").fieldNames()).toIterable().doesNotContain(
                    "candidateHandles", "repositoryId", "repoId", "revision", "expectedRevision");
        }
        JsonNode method = mapper.readTree(factory.createSchema(GetMethodSourcePlanningInput.class));
        assertThat(method.path("required")).extracting(JsonNode::asText)
                .contains("questionToResolve", "rationale", "target");
        assertThat(method.at("/properties/target/properties/sourceType/properties/javaType/properties/packageName/description").asText())
                .contains("package");
        assertThat(method.at("/properties/target/properties/parameterTypes/description").asText()).contains("parameter");
        JsonNode members = mapper.readTree(factory.createSchema(DiscoverTypeMembersPlanningInput.class));
        assertThat(members.path("required")).extracting(JsonNode::asText)
                .contains("sourceType", "memberKinds")
                .doesNotContain("offset", "limit");
        assertThat(members.path("properties").has("namePrefix")).isTrue();
        JsonNode segment = mapper.readTree(factory.createSchema(GetSourceSegmentPlanningInput.class));
        assertThat(segment.path("required")).extracting(JsonNode::asText)
                .contains("location").doesNotContain("contextLines");
        JsonNode concept = mapper.readTree(factory.createSchema(ResolveConceptPlanningInput.class));
        JsonNode evidence = mapper.readTree(factory.createSchema(GetEvidenceSourcePlanningInput.class));
        assertThat(concept.at("/properties/identity/anyOf").size()).isEqualTo(10);
        assertThat(concept.toString()).contains("TYPE", "METHOD", "FIELD", "ANNOTATION_USAGE", "TYPE_USAGE",
                "API_ROUTE", "MQ_DESTINATION", "SCHEDULE", "MAPPER_STATEMENT", "MAPPER_STATEMENT_VARIANT",
                "sourceType");
        assertThat(evidence.at("/properties/identity/anyOf").size()).isEqualTo(3);
        assertThat(evidence.toString()).contains("ANNOTATION_SQL", "MAPPER_STATEMENT", "MAPPER_FRAGMENT",
                "fragmentIdentity", "MAPPER_XML_ELEMENT", "ANNOTATION_SQL_TEXT", "\"minimum\":0");
    }

    @Test
    void retains_closed_answer_statement_constraints() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode schema = mapper.readTree(new SpringAiPlanningToolSchemaFactory().createSchema(SubmitAnswerPlanningInput.class));
        JsonNode fact = schema.path("properties").path("facts").path("items");
        JsonNode resolution = schema.path("properties").path("resolutions").path("items");

        assertThat(schema.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(
                        "facts", "uncertainties", "limitations", "questions", "resolutions");
        assertThat(schema.toString()).doesNotContain("anyOf", "allOf");
        for (String group : List.of("facts", "uncertainties", "limitations", "questions")) {
            assertThat(schema.path("properties").path(group).path("items").path("properties").has("type"))
                    .isFalse();
        }
        assertThat(fact.path("additionalProperties").asBoolean()).isFalse();
        assertThat(fact.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(
                        "statementId", "text", "claimId", "citationHandles", "observationIds");
        assertThat(fact.path("properties").path("citationHandles").path("minItems").asInt()).isEqualTo(1);
        assertThat(fact.path("properties").path("citationHandles").path("items").path("minLength").asInt())
                .isEqualTo(1);
        for (String group : List.of("uncertainties", "limitations", "questions")) {
            JsonNode nonFact = schema.path("properties").path(group).path("items");
            assertThat(nonFact.path("additionalProperties").asBoolean()).isFalse();
            assertThat(nonFact.path("properties").has("claimId")).isFalse();
            assertThat(nonFact.path("required")).extracting(JsonNode::asText)
                    .containsExactlyInAnyOrder("statementId", "text", "citationHandles", "observationIds");
        }
        assertThat(resolution.path("additionalProperties").asBoolean()).isFalse();
        assertThat(resolution.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("needId", "status", "evidenceHandles", "observationIds");
        JsonNode evidenceHandles = resolution.path("properties").path("evidenceHandles");
        assertThat(evidenceHandles.path("description").asText())
                .contains("one complete issued opaque evidence handle", "must not be joined");
        assertThat(evidenceHandles.path("items").path("pattern").asText()).isNotBlank();
    }

    @Test
    void retains_question_planning_information_need_constraints() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode schema = mapper.readTree(new SpringAiPlanningToolSchemaFactory().createSchema(PlanQuestionPlanningInput.class));
        JsonNode needs = schema.path("properties").path("needs");
        JsonNode informationNeed = needs.path("items");

        assertThat(schema.path("properties").fieldNames()).toIterable().containsExactly("needs");
        assertThat(needs.path("minItems").asInt()).isEqualTo(1);
        assertThat(needs.path("maxItems").asInt()).isEqualTo(12);
        assertThat(informationNeed.path("additionalProperties").asBoolean()).isFalse();
        assertThat(informationNeed.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("id", "description");
        assertThat(informationNeed.path("properties").path("id").path("maxLength").asInt()).isEqualTo(32);
    }

}
