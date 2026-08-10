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
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.candidate.SemanticTargetCandidate;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.answering.port.out.AgentPromptContext;
import com.java.system.agent.model.prompt.AgentPromptResourceProperties;
import com.java.system.agent.model.prompt.PromptResourceCatalog;
import com.java.system.agent.model.prompt.PromptResourceCatalogLoader;
import com.java.system.agent.codeintelligence.CodeIntelligenceQuery;
import com.java.system.agent.codeintelligence.planning.CodeIntelligencePlanningToolProvider;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticCandidateTargetMapper;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticResultMapper;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
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
        CapabilityPolicy unissuedPolicy = new CapabilityPolicy("test_lookup_unissued", "v1",
                Set.of(CandidateKind.REPOSITORY), 1, 1);
        PlanningToolRegistry registry = registry(policy, unissuedPolicy);
        PromptResourceCatalog catalog = catalog(registry);

        SpringAiPlanningToolCallbackAdapter adapter = new SpringAiPlanningToolCallbackAdapter(registry, schemaFactory, catalog);

        assertThat(schemaFactory.verified()).extracting(verified -> verified.inputType().getName())
                .containsExactly(TestLookupPlanningInput.class.getName(), TestLookupPlanningInput.class.getName());
        assertThat(schemaFactory.verified()).allSatisfy(verified ->
                assertThat(verified.projectedSchema()).contains("\"additionalProperties\":false"));
        IssuedPlanningTools issued = adapter.issuedTools(context(policy));

        assertThat(issued.names()).containsExactly("test_lookup_symbol");
        assertThat(issued.callbacks()).extracting(toolCallback -> toolCallback.getToolDefinition().name())
                .containsExactlyElementsOf(issued.names());
        assertThat(issued.callbacks()).singleElement().satisfies(toolCallback -> {
            assertThat(toolCallback.getToolDefinition().description()).isNotBlank();
            assertThat(toolCallback.getToolDefinition().inputSchema()).contains("questionToResolve");
        });
        assertThat(issued.names()).doesNotContain("test_lookup_unissued");
        assertThat(adapter.issuedCallbacks(context(policy))).containsExactlyElementsOf(issued.callbacks());
        assertThat(catalog.resourceDigests().keySet())
                .noneMatch(logicalId -> logicalId.contains("test_lookup_symbol"));
    }

    @Test
    void issuesSourceSegmentNamesAndCallbacksOnlyForCompatibleCandidateAuthority() {
        CanonicalCapabilityPayloadCodec payloadCodec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        PlanningToolRegistry registry = new PlanningToolRegistry(List.of(new CodeIntelligencePlanningToolProvider(
                org.mockito.Mockito.mock(JavaSemanticServiceHttpAdapter.class), payloadCodec)),
                new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator()), payloadCodec);
        CapabilityPolicy sourceSegment = registry.availableCapabilities().stream()
                .filter(policy -> policy.name().equals(CodeIntelligenceQuery.GET_SOURCE_SEGMENT.capabilityName()))
                .findFirst().orElseThrow();
        CapabilityPolicy typeMembers = registry.availableCapabilities().stream()
                .filter(policy -> policy.name().equals(CodeIntelligenceQuery.DISCOVER_TYPE_MEMBERS.capabilityName()))
                .findFirst().orElseThrow();
        SpringAiPlanningToolCallbackAdapter adapter = new SpringAiPlanningToolCallbackAdapter(registry,
                new SpringAiPlanningToolSchemaFactory(), catalog(registry));
        SemanticDtos.SourceRangePayload range = new SemanticDtos.SourceRangePayload("src/Orders.java",
                new SemanticDtos.TextRangePayload(new SemanticDtos.Position(0, 0), new SemanticDtos.Position(1, 2)));
        AgentPromptContext sourceRangeContext = semanticContext(sourceSegment, new JavaSemanticCandidateTargetMapper()
                .semanticTarget(range));
        AgentPromptContext methodContext = semanticContext(sourceSegment, new JavaSemanticResultMapper().semanticTarget(
                new SemanticDtos.MethodTarget("src/Orders.java", "com.example", "Orders", "find", List.of())));
        AgentPromptContext typeMemberContext = semanticContext(typeMembers, new JavaSemanticResultMapper().semanticTarget(
                new SemanticDtos.MethodTarget("src/Orders.java", "com.example", "Orders", "find", List.of())));

        IssuedPlanningTools sourceRangeTools = adapter.issuedTools(sourceRangeContext);
        IssuedPlanningTools methodTools = adapter.issuedTools(methodContext);
        IssuedPlanningTools typeMemberTools = adapter.issuedTools(typeMemberContext);

        assertThat(sourceRangeTools.names()).contains(CodeIntelligenceQuery.GET_SOURCE_SEGMENT.capabilityName());
        assertThat(sourceRangeTools.callbacks()).extracting(callback -> callback.getToolDefinition().name())
                .contains(CodeIntelligenceQuery.GET_SOURCE_SEGMENT.capabilityName());
        assertThat(methodTools.names()).doesNotContain(CodeIntelligenceQuery.GET_SOURCE_SEGMENT.capabilityName());
        assertThat(methodTools.callbacks()).extracting(callback -> callback.getToolDefinition().name())
                .doesNotContain(CodeIntelligenceQuery.GET_SOURCE_SEGMENT.capabilityName());
        assertThat(sourceRangeTools.callbacks()).extracting(callback -> callback.getToolDefinition().inputSchema())
                .allSatisfy(schema -> assertThat(schema).doesNotContain("sourceType", "repoId", "expectedRevision",
                        "offset", "location"));
        assertThat(typeMemberTools.callbacks()).extracting(callback -> callback.getToolDefinition().inputSchema())
                .allSatisfy(schema -> assertThat(schema).doesNotContain("sourceType", "repoId", "expectedRevision",
                        "offset"));
    }

    private static PlanningToolRegistry registry(CapabilityPolicy policy, CapabilityPolicy unissuedPolicy) {
        CanonicalCapabilityPayloadCodec payloadCodec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        PlanningToolProvider provider = () -> List.of(registration(policy, payloadCodec),
                registration(unissuedPolicy, payloadCodec));
        return new PlanningToolRegistry(List.of(provider), new StrictPlanningToolDecoder(
                Validation.buildDefaultValidatorFactory().getValidator()), new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator()));
    }

    private static QueryPlanningToolRegistration<TestLookupPlanningInput, String> registration(
            CapabilityPolicy policy,
            CanonicalCapabilityPayloadCodec payloadCodec) {
        return new QueryPlanningToolRegistration<>(policy, TestLookupPlanningInput.class, String.class,
                input -> new QueryPlanningSelection<>(List.of(), input.questionToResolve(), "test rationale", "input"),
                (context, input) -> new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of()), payloadCodec);
    }

    private static PromptResourceCatalog catalog(PlanningToolRegistry registry) {
        AgentPromptResourceProperties properties = new AgentPromptResourceProperties(
                "classpath:/prompts/action/system.md", "classpath:/prompts/action/context.st",
                "classpath:/prompts/action/latest-answer-feedback.st",
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

    private static AgentPromptContext semanticContext(CapabilityPolicy policy,
                                                      com.java.system.agent.answering.domain.evidence.SemanticTarget target) {
        RepositoryId repositoryId = new RepositoryId("orders");
        RepositoryRevision revision = new RepositoryRevision("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, revision);
        AnalysisRunId runId = new AnalysisRunId("run-1");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        HandleBinding binding = new HandleBinding(runId, attemptId, revisions);
        CapabilityHandle capabilityHandle = new CapabilityHandle("capability-source-segment", binding);
        com.java.system.agent.answering.domain.handle.CandidateHandle candidateHandle =
                new com.java.system.agent.answering.domain.handle.CandidateHandle("candidate-semantic", binding,
                        CandidateKind.SEMANTIC_TARGET);
        IssuedCandidate candidate = new IssuedCandidate(candidateHandle,
                new SemanticTargetCandidate(repositoryId, revision, target, "Semantic candidate"));
        return new AgentPromptContext("Read source", SessionHistory.empty(), runId, attemptId,
                Map.of(capabilityHandle, policy), Map.of(candidateHandle, candidate), Map.of(), Map.of(), List.of(),
                Optional.empty(), new AttemptBudget(3, 0, 3, 0, 1, 0, 3, 0, 1, 0));
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
