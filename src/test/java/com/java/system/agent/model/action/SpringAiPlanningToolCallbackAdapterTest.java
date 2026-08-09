package com.java.system.agent.model.action;

import com.java.system.agent.capability.planning.AnswerPlanningToolRegistration;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.capability.planning.ClarifyPlanningToolRegistration;
import com.java.system.agent.capability.planning.PlanningToolProvider;
import com.java.system.agent.capability.planning.PlanningToolRegistry;
import com.java.system.agent.capability.planning.PlanningToolSchemaFactory;
import com.java.system.agent.capability.planning.RequestClarificationPlanningInput;
import com.java.system.agent.capability.planning.RequestClarificationPlanningMapper;
import com.java.system.agent.capability.planning.StrictPlanningToolDecoder;
import com.java.system.agent.capability.planning.SubmitAnswerPlanningInput;
import com.java.system.agent.capability.planning.SubmitAnswerPlanningMapper;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.port.out.AgentPromptContext;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring AI planning callback 在啟動時投影與驗證完整 catalog 的邊界測試
 */
class SpringAiPlanningToolCallbackAdapterTest {

    @Test
    void eagerlyProjectsEveryRegistrationAndVerifiesTheActualCallbackSchema() {
        TrackingSchemaFactory schemaFactory = new TrackingSchemaFactory();
        PlanningToolRegistry registry = registry();

        SpringAiPlanningToolCallbackAdapter adapter = new SpringAiPlanningToolCallbackAdapter(registry, schemaFactory);

        assertThat(schemaFactory.verified()).extracting(verified -> verified.inputType().getName()).containsExactly(
                RequestClarificationPlanningInput.class.getName(), SubmitAnswerPlanningInput.class.getName());
        assertThat(schemaFactory.verified()).allSatisfy(verified ->
                assertThat(verified.projectedSchema()).contains("\"additionalProperties\":false"));
        assertThat(adapter.issuedCallbacks(context())).extracting(toolCallback -> toolCallback.getToolDefinition())
                .extracting(definition -> definition.name())
                .containsExactly("agent_request_clarification", "agent_submit_answer");
        assertThat(adapter.issuedCallbacks(context()))
                .filteredOn(toolCallback -> toolCallback.getToolDefinition().name().equals("agent_submit_answer"))
                .singleElement()
                .satisfies(toolCallback -> assertThat(toolCallback.getToolDefinition().description())
                        .contains("Evidence coverage by capability must contain evidence handles")
                        .contains("explicitly requested evidence type")
                        .contains("Do not substitute another evidence type"));
    }

    private static PlanningToolRegistry registry() {
        PlanningToolProvider provider = () -> List.of(
                new AnswerPlanningToolRegistration<>("agent_submit_answer", SubmitAnswerPlanningInput.class,
                        new SubmitAnswerPlanningMapper()),
                new ClarifyPlanningToolRegistration<>("agent_request_clarification",
                        RequestClarificationPlanningInput.class, new RequestClarificationPlanningMapper()));
        return new PlanningToolRegistry(List.of(provider), new StrictPlanningToolDecoder(
                Validation.buildDefaultValidatorFactory().getValidator()), new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator()));
    }

    private static AgentPromptContext context() {
        AnalysisRunId runId = new AnalysisRunId("run-1");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        return new AgentPromptContext("Find routes", SessionHistory.empty(), runId, attemptId, Map.of(), Map.of(),
                Map.of(), Map.of(), List.of(), Optional.empty(), new AttemptBudget(3, 0, 3, 0, 1, 0, 3, 0, 1, 0));
    }

    private record Verified(Class<?> inputType, String projectedSchema) {
    }

    private static final class TrackingSchemaFactory implements PlanningToolSchemaFactory {

        private final SpringAiPlanningToolSchemaFactory delegate = new SpringAiPlanningToolSchemaFactory();
        private final List<Verified> verified = new ArrayList<>();

        @Override
        public String createSchema(Class<?> inputType) {
            return delegate.createSchema(inputType);
        }

        @Override
        public void verifySchema(Class<?> inputType, String projectedSchema) {
            delegate.verifySchema(inputType, projectedSchema);
            verified.add(new Verified(inputType, projectedSchema));
        }

        private List<Verified> verified() {
            return List.copyOf(verified);
        }
    }
}
