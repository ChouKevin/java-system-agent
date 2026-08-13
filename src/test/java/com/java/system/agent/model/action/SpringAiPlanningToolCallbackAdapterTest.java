package com.java.system.agent.model.action;

import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.capability.planning.CorePlanningToolProvider;
import com.java.system.agent.capability.planning.PlanningToolProvider;
import com.java.system.agent.capability.planning.PlanningToolRegistry;
import com.java.system.agent.capability.planning.PlanningToolSchemaFactory;
import com.java.system.agent.capability.planning.QueryPlanningSelection;
import com.java.system.agent.capability.planning.QueryPlanningToolRegistration;
import com.java.system.agent.capability.planning.StrictPlanningToolDecoder;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.candidate.FollowUpCandidate;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.candidate.RepositoryCandidate;
import com.java.system.agent.answering.domain.candidate.SemanticTargetCandidate;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.action.PlanAction;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.ActionResult;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.ModelInteraction;
import com.java.system.agent.answering.domain.plan.InformationNeed;
import com.java.system.agent.answering.domain.plan.InformationNeedId;
import com.java.system.agent.answering.domain.plan.QuestionPlan;
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
import com.java.system.agent.codeintelligence.planning.FindInternalReferencesExecutionInput;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticCandidateTargetMapper;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticResultMapper;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Spring AI planning callback 在啟動時投影與驗證完整 catalog 的邊界測試
 */
class SpringAiPlanningToolCallbackAdapterTest {

    @Test
    void projects_each_issued_tool_from_one_registry_snapshot() {
        CanonicalCapabilityPayloadCodec payloadCodec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        PlanningToolRegistry registry = spy(new PlanningToolRegistry(List.of(new CorePlanningToolProvider()),
                new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator()), payloadCodec));
        AgentPromptContext context = clarificationAuthorityContext();
        SpringAiPlanningToolCallbackAdapter adapter = new SpringAiPlanningToolCallbackAdapter(registry,
                new SpringAiPlanningToolSchemaFactory(), catalog(registry));

        IssuedPlanningTools issued = adapter.issuedTools(context);

        assertThat(issued.names()).containsExactly("agent_request_clarification", "agent_submit_answer");
        assertThat(issued.callbacks()).extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("agent_request_clarification", "agent_submit_answer");
        verify(registry, times(1)).issuedTools(context);
        verify(registry, never()).issuedRegistrations(context);
    }

    @Test
    void projectsCallbackNamesFromTheRegistrySnapshotInBothQuestionPlanningPhases() {
        CanonicalCapabilityPayloadCodec payloadCodec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        PlanningToolRegistry registry = new PlanningToolRegistry(List.of(new CorePlanningToolProvider()),
                new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator()), payloadCodec);
        SpringAiPlanningToolCallbackAdapter adapter = new SpringAiPlanningToolCallbackAdapter(registry,
                new SpringAiPlanningToolSchemaFactory(), catalog(registry));

        IssuedPlanningTools beforePlan = adapter.issuedTools(context(List.of()));
        IssuedPlanningTools afterPlan = adapter.issuedTools(context(questionPlanInteractions()));

        assertThat(beforePlan.names()).containsExactly("agent_plan_question");
        assertThat(beforePlan.callbacks()).extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyElementsOf(beforePlan.names());
        assertThat(afterPlan.names()).contains("agent_submit_answer", "agent_request_clarification")
                .doesNotContain("agent_plan_question");
        assertThat(afterPlan.callbacks()).extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyElementsOf(afterPlan.names());
    }

    @Test
    void rendersGenericQueryCallbackDescriptionFromTheImmutableCatalog() {
        TrackingSchemaFactory schemaFactory = new TrackingSchemaFactory();
        CapabilityPolicy policy = new CapabilityPolicy("test_lookup_symbol", "v1");
        CapabilityPolicy unissuedPolicy = new CapabilityPolicy("test_lookup_unissued", "v1");
        PlanningToolRegistry registry = registry(policy, unissuedPolicy);
        PromptResourceCatalog catalog = catalog(registry);

        SpringAiPlanningToolCallbackAdapter adapter = new SpringAiPlanningToolCallbackAdapter(registry, schemaFactory, catalog);

        assertThat(schemaFactory.created()).extracting(created -> created.inputType().getName())
                .containsExactly(TestLookupPlanningInput.class.getName(), TestLookupPlanningInput.class.getName());
        assertThat(schemaFactory.created()).allSatisfy(created ->
                assertThat(created.schema()).contains("\"additionalProperties\":false"));
        IssuedPlanningTools issued = adapter.issuedTools(context(policy));

        assertThat(issued.names()).containsExactly("test_lookup_symbol");
        assertThat(issued.callbacks()).extracting(toolCallback -> toolCallback.getToolDefinition().name())
                .containsExactlyElementsOf(issued.names());
        assertThat(issued.callbacks()).singleElement().satisfies(toolCallback -> {
            assertThat(toolCallback.getToolDefinition().description()).isNotBlank();
            String registeredSchema = toolCallback.getToolDefinition().inputSchema();
            assertThat(registeredSchema).contains("questionToResolve");
            assertThat(schemaFactory.created()).allSatisfy(created ->
                    assertThat(registeredSchema).isEqualTo(created.schema()));
        });
        assertThat(issued.names()).doesNotContain("test_lookup_unissued");
        assertThat(adapter.issuedCallbacks(context(policy))).containsExactlyElementsOf(issued.callbacks());
        assertThat(catalog.resourceDigests().keySet())
                .noneMatch(logicalId -> logicalId.contains("test_lookup_symbol"));
    }

    @Test
    void issuesSourceSegmentAndTypeMemberToolsFromCurrentCapabilityWithoutCandidateAuthority() {
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
        assertThat(methodTools.names()).contains(CodeIntelligenceQuery.GET_SOURCE_SEGMENT.capabilityName());
        String sourceSegmentSchema = sourceRangeTools.callbacks().stream()
                .filter(callback -> callback.getToolDefinition().name()
                        .equals(CodeIntelligenceQuery.GET_SOURCE_SEGMENT.capabilityName()))
                .findFirst().orElseThrow().getToolDefinition().inputSchema();
        String typeMembersSchema = typeMemberTools.callbacks().stream()
                .filter(callback -> callback.getToolDefinition().name()
                        .equals(CodeIntelligenceQuery.DISCOVER_TYPE_MEMBERS.capabilityName()))
                .findFirst().orElseThrow().getToolDefinition().inputSchema();
        assertThat(sourceSegmentSchema).contains("location", "contextLines")
                .doesNotContain("candidateHandles", "repoId", "expectedRevision");
        assertThat(typeMembersSchema).contains("sourceType", "memberKinds", "offset")
                .doesNotContain("candidateHandles", "repoId", "expectedRevision");
    }

    @Test
    void issuesOneCandidateFreeQuerySnapshotForCurrentCapabilities() {
        CanonicalCapabilityPayloadCodec payloadCodec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        PlanningToolRegistry registry = new PlanningToolRegistry(List.of(new CodeIntelligencePlanningToolProvider(
                org.mockito.Mockito.mock(JavaSemanticServiceHttpAdapter.class), payloadCodec)),
                new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator()), payloadCodec);
        SpringAiPlanningToolCallbackAdapter adapter = new SpringAiPlanningToolCallbackAdapter(registry,
                new SpringAiPlanningToolSchemaFactory(), catalog(registry));

        IssuedPlanningTools issued = adapter.issuedTools(mixedAuthorityContext(registry, payloadCodec));

        assertThat(issued.names()).containsExactlyInAnyOrder(java.util.Arrays.stream(CodeIntelligenceQuery.values())
                .map(CodeIntelligenceQuery::capabilityName).toArray(String[]::new));
        assertThat(issued.callbacks()).extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyElementsOf(issued.names());
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
                input -> new QueryPlanningSelection<>(input.questionToResolve(), "test rationale", "input"),
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
        HandleBinding binding = new HandleBinding(runId, attemptId, RevisionVector.empty().pin(
                new RepositoryId("repository-a"), new RepositoryRevision("revision-a")));
        return new AgentPromptContext("Find routes", SessionHistory.empty(), runId, attemptId,
                Map.of(new CapabilityHandle("capability-1", binding), policy), Map.of(),
                Map.of(), Map.of(), questionPlanInteractions(), Optional.empty(),
                new AttemptBudget(3, 0, 3, 0, 1, 0, 3, 0, 1, 0));
    }

    private static AgentPromptContext context(List<ModelInteraction> modelInteractions) {
        AnalysisRunId runId = new AnalysisRunId("run-1");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        return new AgentPromptContext("Find routes", SessionHistory.empty(), runId, attemptId, Map.of(), Map.of(),
                Map.of(), Map.of(), modelInteractions, Optional.empty(),
                new AttemptBudget(3, 0, 3, 0, 1, 0, 3, 0, 1, 0));
    }

    private static AgentPromptContext clarificationAuthorityContext() {
        AnalysisRunId runId = new AnalysisRunId("run-1");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        HandleBinding binding = new HandleBinding(runId, attemptId, RevisionVector.empty());
        CandidateHandle firstHandle = new CandidateHandle("candidate-first", binding, CandidateKind.REPOSITORY);
        CandidateHandle secondHandle = new CandidateHandle("candidate-second", binding, CandidateKind.REPOSITORY);
        Map<CandidateHandle, IssuedCandidate> candidates = new LinkedHashMap<>();
        candidates.put(firstHandle, new IssuedCandidate(firstHandle,
                new RepositoryCandidate(new RepositoryId("first"), "First repository candidate")));
        candidates.put(secondHandle, new IssuedCandidate(secondHandle,
                new RepositoryCandidate(new RepositoryId("second"), "Second repository candidate")));
        return new AgentPromptContext("Find routes", SessionHistory.empty(), runId, attemptId, Map.of(), candidates,
                Map.of(), Map.of(), questionPlanInteractions(), Optional.empty(),
                new AttemptBudget(3, 0, 3, 0, 1, 0, 3, 0, 1, 0));
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
                Map.of(capabilityHandle, policy), Map.of(candidateHandle, candidate), Map.of(), Map.of(), questionPlanInteractions(),
                Optional.empty(), new AttemptBudget(3, 0, 3, 0, 1, 0, 3, 0, 1, 0));
    }

    private static AgentPromptContext mixedAuthorityContext(
            PlanningToolRegistry registry,
            CanonicalCapabilityPayloadCodec payloadCodec) {
        RepositoryId repositoryId = new RepositoryId("orders");
        RepositoryRevision revision = new RepositoryRevision("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, revision);
        AnalysisRunId runId = new AnalysisRunId("run-1");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        HandleBinding binding = new HandleBinding(runId, attemptId, revisions);
        Map<CapabilityHandle, CapabilityPolicy> capabilities = new LinkedHashMap<>();
        for (CapabilityPolicy policy : registry.availableCapabilities()) {
            capabilities.put(new CapabilityHandle("capability-" + policy.name(), binding), policy);
        }
        SemanticDtos.SourceTypeIdentityPayload sourceType = new SemanticDtos.SourceTypeIdentityPayload(
                new SemanticDtos.JavaTypeIdentityPayload("com.example", "Orders"), "src/Orders.java");
        SemanticDtos.MethodTargetPayload methodTarget = new SemanticDtos.MethodTargetPayload(
                sourceType, "find", List.of());
        SemanticDtos.SourceRangePayload sourceRange = new SemanticDtos.SourceRangePayload("src/Orders.java",
                new SemanticDtos.TextRangePayload(new SemanticDtos.Position(0, 0), new SemanticDtos.Position(1, 2)));
        CandidateHandle methodHandle = new CandidateHandle("candidate-method", binding, CandidateKind.SEMANTIC_TARGET);
        CandidateHandle sourceRangeHandle = new CandidateHandle("candidate-source-range", binding,
                CandidateKind.SEMANTIC_TARGET);
        CandidateHandle internalReferencesHandle = new CandidateHandle("candidate-internal-references", binding,
                CandidateKind.FOLLOW_UP);
        HandleBinding staleBinding = new HandleBinding(runId, new AnalysisAttemptId("attempt-0"), revisions);
        CandidateHandle staleFollowUpHandle = new CandidateHandle("candidate-stale", staleBinding,
                CandidateKind.FOLLOW_UP);
        CapabilityPolicy internalReferencesPolicy = policy(registry, CodeIntelligenceQuery.FIND_INTERNAL_REFERENCES);
        Map<CandidateHandle, IssuedCandidate> candidates = new LinkedHashMap<>();
        candidates.put(methodHandle, new IssuedCandidate(methodHandle, new SemanticTargetCandidate(repositoryId, revision,
                new JavaSemanticResultMapper().semanticTarget(new SemanticDtos.MethodTarget("src/Orders.java",
                        "com.example", "Orders", "find", List.of())), "Method target")));
        candidates.put(sourceRangeHandle, new IssuedCandidate(sourceRangeHandle, new SemanticTargetCandidate(repositoryId,
                revision, new JavaSemanticCandidateTargetMapper().semanticTarget(sourceRange), "Source range target")));
        candidates.put(internalReferencesHandle, new IssuedCandidate(internalReferencesHandle, new FollowUpCandidate(
                repositoryId, revision, internalReferencesPolicy.name(), internalReferencesPolicy.version(),
                payloadCodec.encode(new FindInternalReferencesExecutionInput(
                        new SemanticDtos.InternalReferenceFollowUpTarget("METHOD", methodTarget), 0, 10)),
                "Find internal references")));
        candidates.put(staleFollowUpHandle, new IssuedCandidate(staleFollowUpHandle, new FollowUpCandidate(repositoryId, revision,
                CodeIntelligenceQuery.GET_EVIDENCE_SOURCE.capabilityName(), CodeIntelligenceQuery.GET_EVIDENCE_SOURCE.version(),
                new CapabilityInputPayload("{\"unrelated\":true}"), "Stale follow-up")));
        return new AgentPromptContext("Read source", SessionHistory.empty(), runId, attemptId, capabilities, candidates,
                Map.of(), Map.of(), questionPlanInteractions(), Optional.empty(),
                new AttemptBudget(4, 0, 3, 0, 1, 0, 3, 0, 1, 0));
    }

    private static List<ModelInteraction> questionPlanInteractions() {
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        QuestionPlan plan = new QuestionPlan(List.of(
                new InformationNeed(new InformationNeedId("scope"), "確認業務範圍")));
        return List.of(
                new ModelInteraction.ActionSelected(attemptId, new PlanAction(plan)),
                new ModelInteraction.ActionResultRecorded(attemptId, new ActionResult.QuestionPlanRecorded(plan)));
    }

    private static CapabilityPolicy policy(PlanningToolRegistry registry, CodeIntelligenceQuery query) {
        return registry.availableCapabilities().stream()
                .filter(policy -> policy.name().equals(query.capabilityName()))
                .findFirst()
                .orElseThrow();
    }

    private record TestLookupPlanningInput(String questionToResolve) {
    }

    private record Created(Class<?> inputType, String schema) {
    }

    private static final class TrackingSchemaFactory implements PlanningToolSchemaFactory {

        private final SpringAiPlanningToolSchemaFactory delegate = new SpringAiPlanningToolSchemaFactory();
        private final List<Created> created = new ArrayList<>();

        @Override
        public String createSchema(Class<?> inputType) {
            String schema = delegate.createSchema(inputType);
            created.add(new Created(inputType, schema));
            return schema;
        }

        private List<Created> created() {
            return List.copyOf(created);
        }
    }
}
