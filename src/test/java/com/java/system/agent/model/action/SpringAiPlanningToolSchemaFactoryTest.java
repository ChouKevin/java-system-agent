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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiPlanningToolSchemaFactoryTest {

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
    }

    @Test
    void retains_closed_answer_statement_constraints() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode schema = mapper.readTree(new SpringAiPlanningToolSchemaFactory().createSchema(SubmitAnswerPlanningInput.class));
        JsonNode statement = schema.path("properties").path("statements").path("items");
        JsonNode resolution = schema.path("properties").path("resolutions").path("items");

        assertThat(schema.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("statements", "resolutions");
        assertThat(statement.path("additionalProperties").asBoolean()).isFalse();
        assertThat(statement.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("statementId", "type", "text", "citationHandles", "observationIds");
        assertThat(statement.path("properties").path("citationHandles").path("items").path("minLength").asInt())
                .isEqualTo(1);
        assertThat(resolution.path("additionalProperties").asBoolean()).isFalse();
        assertThat(resolution.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("needId", "status", "evidenceHandles", "observationIds");
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
