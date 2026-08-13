package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.answering.domain.action.PlanAction;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.plan.InformationNeed;
import com.java.system.agent.answering.domain.plan.InformationNeedId;
import com.java.system.agent.answering.domain.plan.QuestionPlan;
import com.java.system.agent.answering.domain.run.ActionResult;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.ModelInteraction;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.out.AgentPromptContext;
import com.java.system.agent.answering.port.out.AgentActionProposal;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.capability.planning.PlanningToolRegistry;
import com.java.system.agent.capability.planning.StrictPlanningToolDecoder;
import com.java.system.agent.codeintelligence.CodeIntelligenceQuery;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticServiceHttpAdapter;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CodeIntelligencePlanningToolProviderTest {

    @Test
    void issues_every_semantic_query_once_after_plan_without_candidates() {
        CanonicalCapabilityPayloadCodec codec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        PlanningToolRegistry registry = new PlanningToolRegistry(List.of(new CodeIntelligencePlanningToolProvider(
                org.mockito.Mockito.mock(JavaSemanticServiceHttpAdapter.class), codec)),
                new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator()), codec);
        RevisionVector revisions = RevisionVector.empty().pin(new RepositoryId("orders"),
                new RepositoryRevision("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        HandleBinding binding = new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"), revisions);
        Map<CapabilityHandle, com.java.system.agent.answering.domain.capability.CapabilityPolicy> policies = new LinkedHashMap<>();
        for (com.java.system.agent.answering.domain.capability.CapabilityPolicy policy : registry.availableCapabilities()) {
            policies.put(new CapabilityHandle("capability-" + policy.name(), binding), policy);
        }
        QuestionPlan plan = new QuestionPlan(List.of(new InformationNeed(new InformationNeedId("scope"), "Scope")));
        AgentPromptContext context = new AgentPromptContext("Question", SessionHistory.empty(), binding.runId(), binding.attemptId(),
                policies, Map.of(), Map.of(), Map.of(), List.of(new ModelInteraction.ActionSelected(binding.attemptId(), new PlanAction(plan)),
                new ModelInteraction.ActionResultRecorded(binding.attemptId(), new ActionResult.QuestionPlanRecorded(plan))), Optional.empty(),
                new AttemptBudget(3, 0, 3, 0, 1, 0, 3, 0, 1, 0));

        assertThat(registry.issuedRegistrations(context)).extracting(registration -> registration.name())
                .containsExactlyInAnyOrder(java.util.Arrays.stream(CodeIntelligenceQuery.values())
                        .map(CodeIntelligenceQuery::capabilityName).toArray(String[]::new));
    }

    @Test
    void decodes_and_maps_each_typed_query_without_candidates() {
        CanonicalCapabilityPayloadCodec codec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        PlanningToolRegistry registry = new PlanningToolRegistry(List.of(new CodeIntelligencePlanningToolProvider(
                org.mockito.Mockito.mock(JavaSemanticServiceHttpAdapter.class), codec)),
                new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator()), codec);
        AgentPromptContext context = plannedContext(registry);
        Map<String, Class<?>> expectedTypes = Map.ofEntries(
                Map.entry("codebase_list_entry_points", ListEntryPointsExecutionInput.class),
                Map.entry("codebase_lookup_api_route", LookupApiRouteExecutionInput.class),
                Map.entry("codebase_suggest_api_route", SuggestApiRouteExecutionInput.class),
                Map.entry("codebase_outgoing_call_graph", OutgoingCallGraphExecutionInput.class),
                Map.entry("codebase_incoming_call_graph", IncomingCallGraphExecutionInput.class),
                Map.entry("codebase_discover_concepts", DiscoverConceptsExecutionInput.class),
                Map.entry("codebase_resolve_concept", ResolveConceptExecutionInput.class),
                Map.entry("codebase_discover_event_listeners", DiscoverEventListenersExecutionInput.class),
                Map.entry("codebase_discover_method_implementations", DiscoverMethodImplementationsExecutionInput.class),
                Map.entry("codebase_discover_type_members", DiscoverTypeMembersExecutionInput.class),
                Map.entry("codebase_find_internal_references", FindInternalReferencesExecutionInput.class),
                Map.entry("codebase_get_evidence_source", GetEvidenceSourceExecutionInput.class),
                Map.entry("codebase_get_method_source", GetMethodSourceExecutionInput.class),
                Map.entry("codebase_get_source_segment", GetSourceSegmentExecutionInput.class),
                Map.entry("codebase_resolve_source_symbol", ResolveSourceSymbolExecutionInput.class));

        Map<String, Object> decodedInputs = new LinkedHashMap<>();
        for (Map.Entry<String, Class<?>> entry : expectedTypes.entrySet()) {
            AgentActionProposal.Proposed proposed = (AgentActionProposal.Proposed) registry.interpretToolCall(
                    entry.getKey(), json(entry.getKey()), context);
            QueryAction action = (QueryAction) proposed.action();
            Object input = codec.decode(action.payload(), entry.getValue());
            assertThat(input).isInstanceOf(entry.getValue());
            decodedInputs.put(entry.getKey(), input);
        }
        OutgoingCallGraphExecutionInput graph = (OutgoingCallGraphExecutionInput) decodedInputs.get(
                "codebase_outgoing_call_graph");
        DiscoverTypeMembersExecutionInput members = (DiscoverTypeMembersExecutionInput) decodedInputs.get(
                "codebase_discover_type_members");
        ResolveConceptExecutionInput concept = (ResolveConceptExecutionInput) decodedInputs.get(
                "codebase_resolve_concept");
        GetSourceSegmentExecutionInput segment = (GetSourceSegmentExecutionInput) decodedInputs.get(
                "codebase_get_source_segment");
        ResolveSourceSymbolExecutionInput symbol = (ResolveSourceSymbolExecutionInput) decodedInputs.get(
                "codebase_resolve_source_symbol");
        DiscoverConceptsExecutionInput discovery = (DiscoverConceptsExecutionInput) decodedInputs.get(
                "codebase_discover_concepts");

        assertThat(graph.target().methodName()).isEqualTo("find");
        assertThat(members.sourceType().javaType().className()).isEqualTo("Orders");
        assertThat(concept.identity().kind()).isEqualTo("TYPE");
        assertThat(segment.location().range().end().line()).isEqualTo(1);
        assertThat(symbol.context().javaType().packageName()).isEqualTo("com.example");
        assertThat(symbol.symbol()).isEqualTo("find");
        assertThat(discovery.terms()).extracting(DiscoverConceptsExecutionInput.Term::value).containsExactly("orders");
    }

    @Test
    void rejects_invalid_typed_values_before_an_executor_is_selected() {
        CanonicalCapabilityPayloadCodec codec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        JavaSemanticServiceHttpAdapter adapter = org.mockito.Mockito.mock(JavaSemanticServiceHttpAdapter.class);
        PlanningToolRegistry registry = new PlanningToolRegistry(List.of(new CodeIntelligencePlanningToolProvider(
                adapter, codec)),
                new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator()), codec);
        AgentPromptContext context = plannedContext(registry);

        assertThat(registry.interpretToolCall("codebase_outgoing_call_graph", """
                {"questionToResolve":"Read","rationale":"Need evidence","target":
                {"sourceType":{"javaType":{"packageName":"com.example","className":"Orders"},"sourceFile":"src/Orders.java"},
                "methodName":"find","parameterTypes":[]},"depth":3}
                """, context)).isInstanceOf(AgentActionProposal.Malformed.class);
        assertThat(registry.interpretToolCall("codebase_discover_type_members", """
                {"questionToResolve":"Read","rationale":"Need evidence","sourceType":
                {"javaType":{"packageName":"com.example","className":"Orders"},"sourceFile":"src/Orders.java"},
                "memberKinds":["METHOD","METHOD"],"offset":0,"limit":1}
                """, context)).isInstanceOf(AgentActionProposal.Malformed.class);
        assertThat(registry.interpretToolCall("codebase_get_source_segment", """
                {"questionToResolve":"Read","rationale":"Need evidence","location":
                {"sourceFile":"src/Orders.java","range":{"start":{"line":0,"character":0},"end":{"line":1,"character":0}}},
                "contextLines":21}
                """, context)).isInstanceOf(AgentActionProposal.Malformed.class);
        assertThat(registry.interpretToolCall("codebase_resolve_concept", """
                {"questionToResolve":"Read","rationale":"Need evidence","identity":
                {"kind":"TYPE","target":{"sourceType":{"javaType":{"packageName":"com.example","className":"Orders"},"sourceFile":"src/Orders.java"},"methodName":"find","parameterTypes":[]}}}
                """, context)).isInstanceOf(AgentActionProposal.Malformed.class);
        assertThat(registry.interpretToolCall("codebase_outgoing_call_graph", """
                {"questionToResolve":"Read","rationale":"Need evidence","target":
                {"sourceType":{"javaType":{"packageName":"com.example","className":"Orders"},"sourceFile":"../Orders.java"},
                "methodName":"find","parameterTypes":[]}}
                """, context)).isInstanceOf(AgentActionProposal.Malformed.class);
        org.mockito.Mockito.verifyNoInteractions(adapter);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSemanticTargets")
    void rejects_invalid_nested_semantic_targets_before_an_executor_is_selected(
            String scenario, String toolName, String input) {
        CanonicalCapabilityPayloadCodec codec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        JavaSemanticServiceHttpAdapter adapter = org.mockito.Mockito.mock(JavaSemanticServiceHttpAdapter.class);
        PlanningToolRegistry registry = new PlanningToolRegistry(List.of(new CodeIntelligencePlanningToolProvider(adapter, codec)),
                new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator()), codec);

        assertThat(registry.interpretToolCall(toolName, input, plannedContext(registry)))
                .isInstanceOf(AgentActionProposal.Malformed.class);
        org.mockito.Mockito.verifyNoInteractions(adapter);
    }

    @Test
    void accepts_a_default_package_target_with_a_normalized_repository_relative_path() {
        CanonicalCapabilityPayloadCodec codec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        PlanningToolRegistry registry = new PlanningToolRegistry(List.of(new CodeIntelligencePlanningToolProvider(
                org.mockito.Mockito.mock(JavaSemanticServiceHttpAdapter.class), codec)),
                new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator()), codec);

        assertThat(registry.interpretToolCall("codebase_outgoing_call_graph", """
                {"questionToResolve":"Read","rationale":"Need evidence","target":
                {"sourceType":{"javaType":{"packageName":"","className":"Orders"},"sourceFile":"src/Orders.java"},
                "methodName":"find","parameterTypes":[]}}
                """, plannedContext(registry))).isInstanceOf(AgentActionProposal.Proposed.class);
    }

    @Test
    void normalizes_omitted_paging_and_context_defaults_in_the_typed_mappers() {
        CanonicalCapabilityPayloadCodec codec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        PlanningToolRegistry registry = new PlanningToolRegistry(List.of(new CodeIntelligencePlanningToolProvider(
                org.mockito.Mockito.mock(JavaSemanticServiceHttpAdapter.class), codec)),
                new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator()), codec);
        AgentPromptContext context = plannedContext(registry);

        QueryAction graphAction = proposedQuery(registry, "codebase_outgoing_call_graph", """
                {"questionToResolve":"Read","rationale":"Need evidence","target":
                {"sourceType":{"javaType":{"packageName":"com.example","className":"Orders"},"sourceFile":"src/Orders.java"},
                "methodName":"find","parameterTypes":[]}}
                """, context);
        QueryAction conceptsAction = proposedQuery(registry, "codebase_discover_concepts", """
                {"questionToResolve":"Read","rationale":"Need evidence",
                "terms":[{"value":"orders","matchMode":"TOKEN_EXACT"}],"kinds":["TYPE"]}
                """, context);
        QueryAction segmentAction = proposedQuery(registry, "codebase_get_source_segment", """
                {"questionToResolve":"Read","rationale":"Need evidence","location":
                {"sourceFile":"src/Orders.java","range":{"start":{"line":0,"character":0},"end":{"line":1,"character":0}}}}
                """, context);

        assertThat(codec.decode(graphAction.payload(), OutgoingCallGraphExecutionInput.class).depth()).isEqualTo(2);
        DiscoverConceptsExecutionInput concepts = codec.decode(conceptsAction.payload(), DiscoverConceptsExecutionInput.class);
        assertThat(concepts.offset()).isZero();
        assertThat(concepts.limit()).isEqualTo(50);
        assertThat(codec.decode(segmentAction.payload(), GetSourceSegmentExecutionInput.class).contextLines()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSourceRanges")
    void rejects_invalid_source_ranges_as_invalid_tool_input_without_adapter_invocation(String scenario, String input) {
        CanonicalCapabilityPayloadCodec codec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        JavaSemanticServiceHttpAdapter adapter = org.mockito.Mockito.mock(JavaSemanticServiceHttpAdapter.class);
        PlanningToolRegistry registry = new PlanningToolRegistry(List.of(new CodeIntelligencePlanningToolProvider(adapter, codec)),
                new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator()), codec);

        assertThat(registry.interpretToolCall("codebase_get_source_segment", input, plannedContext(registry)))
                .isInstanceOf(AgentActionProposal.Malformed.class);
        org.mockito.Mockito.verifyNoInteractions(adapter);
    }

    private static AgentPromptContext plannedContext(PlanningToolRegistry registry) {
        RevisionVector revisions = RevisionVector.empty().pin(new RepositoryId("orders"),
                new RepositoryRevision("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        HandleBinding binding = new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"), revisions);
        Map<CapabilityHandle, com.java.system.agent.answering.domain.capability.CapabilityPolicy> policies = new LinkedHashMap<>();
        for (com.java.system.agent.answering.domain.capability.CapabilityPolicy policy : registry.availableCapabilities()) {
            policies.put(new CapabilityHandle("capability-" + policy.name(), binding), policy);
        }
        QuestionPlan plan = new QuestionPlan(List.of(new InformationNeed(new InformationNeedId("scope"), "Scope")));
        return new AgentPromptContext("Question", SessionHistory.empty(), binding.runId(), binding.attemptId(), policies,
                Map.of(), Map.of(), Map.of(), List.of(new ModelInteraction.ActionSelected(binding.attemptId(), new PlanAction(plan)),
                new ModelInteraction.ActionResultRecorded(binding.attemptId(), new ActionResult.QuestionPlanRecorded(plan))), Optional.empty(),
                new AttemptBudget(3, 0, 3, 0, 1, 0, 3, 0, 1, 0));
    }

    private static QueryAction proposedQuery(
            PlanningToolRegistry registry,
            String name,
            String input,
            AgentPromptContext context) {
        return (QueryAction) ((AgentActionProposal.Proposed) registry.interpretToolCall(name, input, context)).action();
    }

    private static Stream<Arguments> invalidSourceRanges() {
        return Stream.of(
                Arguments.of("negative coordinate", sourceSegmentInput("{\"line\":-1,\"character\":0}",
                        "{\"line\":0,\"character\":0}")),
                Arguments.of("missing coordinate", sourceSegmentInput("{\"line\":0}",
                        "{\"line\":0,\"character\":0}")),
                Arguments.of("reversed range", sourceSegmentInput("{\"line\":1,\"character\":0}",
                        "{\"line\":0,\"character\":5}")));
    }

    private static Stream<Arguments> invalidSemanticTargets() {
        return Stream.of(
                Arguments.of("dot-dot source path", "codebase_outgoing_call_graph", outgoingTarget("../Orders.java", "Orders", "find", "[]")),
                Arguments.of("absolute source path", "codebase_outgoing_call_graph", outgoingTarget("/src/Orders.java", "Orders", "find", "[]")),
                Arguments.of("backslash source path", "codebase_outgoing_call_graph", outgoingTarget("src\\\\Orders.java", "Orders", "find", "[]")),
                Arguments.of("drive source path", "codebase_outgoing_call_graph", outgoingTarget("C:/Orders.java", "Orders", "find", "[]")),
                Arguments.of("whitespace source path", "codebase_outgoing_call_graph", outgoingTarget("src/Order Service.java", "Orders", "find", "[]")),
                Arguments.of("blank class name", "codebase_outgoing_call_graph", outgoingTarget("src/Orders.java", " ", "find", "[]")),
                Arguments.of("blank method name", "codebase_outgoing_call_graph", outgoingTarget("src/Orders.java", "Orders", " ", "[]")),
                Arguments.of("blank parameter type", "codebase_outgoing_call_graph", outgoingTarget("src/Orders.java", "Orders", "find", "[\" \"]")));
    }

    private static String outgoingTarget(String sourceFile, String className, String methodName, String parameterTypes) {
        return """
                {"questionToResolve":"Read","rationale":"Need evidence","target":{"sourceType":{"javaType":
                {"packageName":"com.example","className":"%s"},"sourceFile":"%s"},"methodName":"%s","parameterTypes":%s}}
                """.formatted(className, sourceFile, methodName, parameterTypes);
    }

    private static String sourceSegmentInput(String start, String end) {
        return """
                {"questionToResolve":"Read","rationale":"Need evidence","location":{"sourceFile":"src/Orders.java",
                "range":{"start":%s,"end":%s}}}
                """.formatted(start, end);
    }

    private static String json(String name) {
        String method = "{\"sourceType\":{\"javaType\":{\"packageName\":\"com.example\",\"className\":\"Orders\"},\"sourceFile\":\"src/Orders.java\"},\"methodName\":\"find\",\"parameterTypes\":[]}";
        String type = "{\"javaType\":{\"packageName\":\"com.example\",\"className\":\"Orders\"},\"sourceFile\":\"src/Orders.java\"}";
        String question = "\"questionToResolve\":\"Read\",\"rationale\":\"Need evidence\",";
        return switch (name) {
            case "codebase_list_entry_points" -> "{" + question + "\"type\":\"API\"}";
            case "codebase_lookup_api_route" -> "{" + question + "\"apiPath\":\"/orders\"}";
            case "codebase_suggest_api_route" -> "{" + question + "\"apiPath\":\"/orders\",\"limit\":1}";
            case "codebase_outgoing_call_graph", "codebase_incoming_call_graph" -> "{" + question + "\"target\":" + method + ",\"depth\":1}";
            case "codebase_discover_concepts" -> "{" + question + "\"terms\":[{\"value\":\"orders\",\"matchMode\":\"TOKEN_EXACT\"}],\"kinds\":[\"TYPE\"],\"offset\":0,\"limit\":1}";
            case "codebase_resolve_concept" -> "{" + question + "\"identity\":{\"kind\":\"TYPE\",\"sourceType\":" + type + "}}";
            case "codebase_discover_event_listeners" -> "{" + question + "\"eventType\":\"OrderCreated\",\"offset\":0,\"limit\":1}";
            case "codebase_discover_method_implementations", "codebase_get_method_source" -> "{" + question + "\"target\":" + method + "}";
            case "codebase_discover_type_members" -> "{" + question + "\"sourceType\":" + type + ",\"memberKinds\":[\"METHOD\"],\"offset\":0,\"limit\":1}";
            case "codebase_find_internal_references" -> "{" + question + "\"target\":{\"kind\":\"METHOD\",\"identity\":" + method + "},\"offset\":0,\"limit\":1}";
            case "codebase_get_evidence_source" -> "{" + question + "\"identity\":{\"kind\":\"MAPPER_FRAGMENT\",\"fragmentIdentity\":{\"namespace\":\"orders\",\"fragmentId\":\"sql\",\"resourcePath\":\"Orders.xml\",\"documentOrdinal\":0,\"representation\":\"XML\"}}}";
            case "codebase_get_source_segment" -> "{" + question + "\"location\":{\"sourceFile\":\"src/Orders.java\",\"range\":{\"start\":{\"line\":0,\"character\":0},\"end\":{\"line\":1,\"character\":0}}},\"contextLines\":0}";
            case "codebase_resolve_source_symbol" -> "{" + question + "\"context\":{\"javaType\":{\"packageName\":\"com.example\",\"className\":\"Orders\"}},\"symbol\":\"find\"}";
            default -> throw new IllegalArgumentException("unknown query");
        };
    }
}
