package com.java.system.agent.model.action;

import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.capability.planning.PlanningToolProvider;
import com.java.system.agent.capability.planning.PlanningToolRegistry;
import com.java.system.agent.capability.planning.PlanningToolSchemaFactory;
import com.java.system.agent.capability.planning.QueryPlanningSelection;
import com.java.system.agent.capability.planning.QueryPlanningToolRegistration;
import com.java.system.agent.capability.planning.StrictPlanningToolDecoder;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.answering.port.out.AgentPromptContext;
import com.java.system.agent.model.prompt.AgentPromptResourceProperties;
import com.java.system.agent.model.prompt.PromptResourceCatalog;
import com.java.system.agent.model.prompt.PromptResourceCatalogLoader;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring AI planning callback 在啟動時投影與驗證完整 catalog 的邊界測試
 */
class SpringAiPlanningToolCallbackAdapterTest {

    @Test
    void rendersGenericQueryCallbackDescriptionFromTheImmutableCatalog() {
        TrackingSchemaFactory schemaFactory = new TrackingSchemaFactory();
        CapabilityPolicy policy = new CapabilityPolicy("test_lookup_symbol", "v1", Set.of(CandidateKind.REPOSITORY), 1, 1);
        PlanningToolRegistry registry = registry(policy);
        PromptResourceCatalog catalog = catalog(registry);

        SpringAiPlanningToolCallbackAdapter adapter = new SpringAiPlanningToolCallbackAdapter(registry, schemaFactory, catalog);

        assertThat(schemaFactory.verified()).extracting(verified -> verified.inputType().getName())
                .containsExactly(TestLookupPlanningInput.class.getName());
        assertThat(schemaFactory.verified()).allSatisfy(verified ->
                assertThat(verified.projectedSchema()).contains("\"additionalProperties\":false"));
        assertThat(adapter.issuedCallbacks(context(policy)))
                .filteredOn(toolCallback -> toolCallback.getToolDefinition().name().equals("test_lookup_symbol"))
                .singleElement()
                .satisfies(toolCallback -> {
                    assertThat(toolCallback.getToolDefinition().description()).isNotBlank();
                    assertThat(toolCallback.getToolDefinition().inputSchema()).contains("questionToResolve");
                });
        assertThat(catalog.resourceDigests().keySet())
                .noneMatch(logicalId -> logicalId.contains("test_lookup_symbol"));
    }

    private static PlanningToolRegistry registry(CapabilityPolicy policy) {
        PlanningToolProvider provider = () -> List.of(new QueryPlanningToolRegistration<>(policy,
                TestLookupPlanningInput.class, String.class,
                input -> new QueryPlanningSelection<>(List.of(), input.questionToResolve(), "test rationale", "input"),
                (context, input) -> new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of()),
                new CanonicalCapabilityPayloadCodec(Validation.buildDefaultValidatorFactory().getValidator())));
        return new PlanningToolRegistry(List.of(provider), new StrictPlanningToolDecoder(
                Validation.buildDefaultValidatorFactory().getValidator()), new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator()));
    }

    private static PromptResourceCatalog catalog(PlanningToolRegistry registry) {
        AgentPromptResourceProperties properties = new AgentPromptResourceProperties(
                "classpath:/prompts/action/system.md", "classpath:/prompts/action/context.st",
                "classpath:/prompts/verification/system.md", "classpath:/prompts/verification/context.st",
                "classpath:/prompts/tools/");
        return new PromptResourceCatalogLoader(new DefaultResourceLoader()).load(properties, registry);
    }

    private static AgentPromptContext context(CapabilityPolicy policy) {
        AnalysisRunId runId = new AnalysisRunId("run-1");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        HandleBinding binding = new HandleBinding(runId, attemptId, RevisionVector.empty());
        return new AgentPromptContext("Find routes", SessionHistory.empty(), runId, attemptId,
                Map.of(new CapabilityHandle("capability-1", binding), policy), Map.of(),
                Map.of(), Map.of(), List.of(), Optional.empty(), new AttemptBudget(3, 0, 3, 0, 1, 0, 3, 0, 1, 0));
    }

    private record TestLookupPlanningInput(String questionToResolve) {
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
