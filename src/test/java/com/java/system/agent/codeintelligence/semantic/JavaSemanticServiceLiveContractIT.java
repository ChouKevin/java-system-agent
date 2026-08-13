package com.java.system.agent.codeintelligence.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.answering.domain.action.PlanAction;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.candidate.AnalysisCandidate;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.candidate.FollowUpCandidate;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.candidate.RepositoryCandidate;
import com.java.system.agent.answering.domain.candidate.SemanticTargetCandidate;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.evidence.EvidenceRef;
import com.java.system.agent.answering.domain.evidence.SemanticTarget;
import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.plan.InformationNeed;
import com.java.system.agent.answering.domain.plan.InformationNeedId;
import com.java.system.agent.answering.domain.plan.QuestionPlan;
import com.java.system.agent.answering.domain.run.ActionResult;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.ModelInteraction;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.out.AgentActionProposal;
import com.java.system.agent.answering.port.out.AgentPromptContext;
import com.java.system.agent.answering.port.out.CapabilityExecutionFailureCode;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.answering.port.out.CapabilityInvocation;
import com.java.system.agent.answering.port.out.RepositoryRevisionFailureCode;
import com.java.system.agent.answering.port.out.RepositoryRevisionResult;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.capability.planning.PlanningToolRegistry;
import com.java.system.agent.capability.planning.StrictPlanningToolDecoder;
import com.java.system.agent.capability.spi.CapabilityExecutionContext;
import com.java.system.agent.codeintelligence.CodeIntelligenceQuery;
import com.java.system.agent.codeintelligence.planning.CodeIntelligencePlanningToolProvider;
import com.java.system.agent.codeintelligence.planning.DiscoverConceptsExecutionInput;
import com.java.system.agent.codeintelligence.planning.DiscoverEventListenersExecutionInput;
import com.java.system.agent.codeintelligence.planning.DiscoverMethodImplementationsExecutionInput;
import com.java.system.agent.codeintelligence.planning.EntryPointType;
import com.java.system.agent.codeintelligence.planning.IncomingCallGraphExecutionInput;
import com.java.system.agent.codeintelligence.planning.ListEntryPointsExecutionInput;
import com.java.system.agent.codeintelligence.planning.LookupApiRouteExecutionInput;
import com.java.system.agent.codeintelligence.planning.OutgoingCallGraphExecutionInput;
import com.java.system.agent.codeintelligence.planning.SuggestApiRouteExecutionInput;
import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 驗證 Agent 對 Java Semantic Service 的 offline 與 live HTTP consumer contract
 */
class JavaSemanticServiceLiveContractIT {

    private static final String OFFLINE_REPOSITORY_ID = "orders";
    private static final String OFFLINE_REVISION = "0123456789012345678901234567890123456789";
    private static final String SOURCE_FILE =
            "src/main/java/com/java/system/agent/codeintelligence/semantic/JavaSemanticServiceHttpAdapter.java";
    private static final String PACKAGE_NAME = "com.java.system.agent.codeintelligence.semantic";
    private static final String CLASS_NAME = "JavaSemanticServiceHttpAdapter";
    private static final String METHOD_NAME = "availableRepositories";
    private static final String UNKNOWN_REPOSITORY_ID = "m5-contract-missing-repository";
    private static final String UNKNOWN_REVISION = "0000000000000000000000000000000000000000";

    private String agentRepositoryId;
    private String agentRevision;
    private String discoveryRepositoryId;
    private String discoveryRevision;
    private RestClient restClient;
    private JavaSemanticServiceHttpAdapter adapter;
    private CanonicalCapabilityPayloadCodec payloadCodec;
    private PlanningToolRegistry registry;

    @Test
    void preserves_provider_issued_implementation_authority_from_graph_edge_to_typed_executor_input() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SemanticDtos.OutgoingCallGraphResponse response = objectMapper.readValue("""
                {
                  "status":"SUCCESS",
                  "analyzedRevision":"0123456789012345678901234567890123456789",
                  "rootNodeId":"abstract-declaration",
                  "traversal":{"requestedDepth":1,"expandedNodeCount":2,"nodeBudget":20,"rootDirectCallsComplete":true,"limitReason":"NONE"},
                  "nodes":[
                    {"nodeId":"abstract-declaration","target":{"sourceType":{"javaType":{"packageName":"com.acme","className":"OrderLookup"},"sourceFile":"src/main/java/com/acme/OrderLookup.java"},"methodName":"findById","parameterTypes":["java.lang.String"]},"externalSymbol":null,"contentState":"FULL_SOURCE","traversalState":"EXPANDED","dispatchKind":"SYNCHRONOUS","declarationRange":null,"availableFollowUps":[]},
                    {"nodeId":"concrete-implementation","target":{"sourceType":{"javaType":{"packageName":"com.acme","className":"JpaOrderLookup"},"sourceFile":"src/main/java/com/acme/JpaOrderLookup.java"},"methodName":"findById","parameterTypes":["java.lang.String"]},"externalSymbol":null,"contentState":"FULL_SOURCE","traversalState":"EXPANDED","dispatchKind":"SYNCHRONOUS","declarationRange":null,"availableFollowUps":[]}
                  ],
                  "edges":[{"callerNodeId":"abstract-declaration","calleeNodeId":"concrete-implementation","callSite":{"sourceFile":"src/main/java/com/acme/OrderService.java","range":{"start":{"line":12,"character":8},"end":{"line":12,"character":22}}},"callExpression":"orderLookup.findById(id)","resolutionStrategy":"SPRING_SINGLE_IMPLEMENTATION","category":"RESOLVED_ANALYZABLE","evidence":["interface dispatch"],"availableFollowUps":[{"operation":"DISCOVER_METHOD_IMPLEMENTATIONS","api":{"method":"POST","path":"/v1/discovery/method-implementations","operationId":"discoverMethodImplementations"},"request":{"repoId":"orders","expectedRevision":"0123456789012345678901234567890123456789","declarationTarget":{"sourceType":{"javaType":{"packageName":"com.acme","className":"OrderLookup"},"sourceFile":"src/main/java/com/acme/OrderLookup.java"},"methodName":"findById","parameterTypes":["java.lang.String"]}}}]}],
                  "warnings":[],
                  "errors":[]
                }
                """, SemanticDtos.OutgoingCallGraphResponse.class);
        new JavaSemanticProviderSchemaValidator().graph(response.status(), response.analyzedRevision(), response.rootNodeId(),
                response.traversal(), response.nodes(), response.edges(), response.warnings(), response.errors());

        RepositoryId repositoryId = new RepositoryId(OFFLINE_REPOSITORY_ID);
        RepositoryRevision revision = new RepositoryRevision(OFFLINE_REVISION);
        SemanticDtos.AvailableFollowUp edgeFollowUp = response.edges().getFirst().availableFollowUps().getFirst();
        assertThat(edgeFollowUp.request()).isInstanceOf(SemanticDtos.DiscoverMethodImplementationsFollowUpRequest.class);
        SemanticDtos.DiscoverMethodImplementationsFollowUpRequest implementationRequest =
                (SemanticDtos.DiscoverMethodImplementationsFollowUpRequest) edgeFollowUp.request();
        SemanticDtos.MethodTargetPayload abstractDeclaration = implementationRequest.declarationTarget();
        CanonicalCapabilityPayloadCodec offlinePayloadCodec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        FollowUpCandidate followUp = new JavaSemanticFollowUpMapper(offlinePayloadCodec).map(
                repositoryId, revision, edgeFollowUp);
        DiscoverMethodImplementationsExecutionInput providerInput = offlinePayloadCodec.decode(
                followUp.payload(), DiscoverMethodImplementationsExecutionInput.class);

        assertThat(followUp.repositoryId()).isEqualTo(repositoryId);
        assertThat(followUp.analyzedRevision()).isEqualTo(revision);
        assertThat(followUp.targetCapabilityName()).isEqualTo(CodeIntelligenceQuery.DISCOVER_METHOD_IMPLEMENTATIONS.capabilityName());
        assertThat(followUp.targetCapabilityVersion()).isEqualTo(CodeIntelligenceQuery.DISCOVER_METHOD_IMPLEMENTATIONS.version());
        assertThat(providerInput.target()).isEqualTo(abstractDeclaration);

        JavaSemanticServiceHttpAdapter offlineAdapter = mock(JavaSemanticServiceHttpAdapter.class);
        when(offlineAdapter.discoverMethodImplementations(any(), any())).thenReturn(
                new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of()));
        PlanningToolRegistry offlineRegistry = new PlanningToolRegistry(
                List.of(new CodeIntelligencePlanningToolProvider(offlineAdapter, offlinePayloadCodec)),
                new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator()), offlinePayloadCodec);
        CapabilityPolicy policy = offlineRegistry.availableCapabilities().stream()
                .filter(value -> value.name().equals(followUp.targetCapabilityName()))
                .findFirst()
                .orElseThrow();
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, revision);
        HandleBinding binding = new HandleBinding(new AnalysisRunId("offline-run"), new AnalysisAttemptId("offline-attempt"), revisions);
        CandidateHandle candidateHandle = new CandidateHandle("implementation-follow-up", binding, CandidateKind.FOLLOW_UP);
        IssuedCandidate issuedCandidate = new IssuedCandidate(candidateHandle, followUp);
        QuestionPlan questionPlan = new QuestionPlan(List.of(new InformationNeed(
                new InformationNeedId("implementation"), "Find the concrete implementation")));
        List<ModelInteraction> plannedInteractions = List.of(
                new ModelInteraction.ActionSelected(binding.attemptId(), new PlanAction(questionPlan)),
                new ModelInteraction.ActionResultRecorded(
                        binding.attemptId(), new ActionResult.QuestionPlanRecorded(questionPlan)));
        AgentPromptContext context = new AgentPromptContext("Which concrete lookup implements the declaration?",
                com.java.system.agent.answering.domain.conversation.SessionHistory.empty(), binding.runId(), binding.attemptId(),
                Map.of(new com.java.system.agent.answering.domain.handle.CapabilityHandle("implementation-capability", binding), policy),
                Map.of(candidateHandle, issuedCandidate), Map.of(), Map.of(), plannedInteractions, Optional.empty(),
                new com.java.system.agent.answering.domain.run.AttemptBudget(3, 0, 3, 0, 1, 0, 3, 0, 1, 0));
        CandidateHandle wrongVersionHandle = new CandidateHandle("wrong-version-follow-up", binding, CandidateKind.FOLLOW_UP);
        FollowUpCandidate wrongVersionFollowUp = new FollowUpCandidate(repositoryId, revision, policy.name(), "v999",
                followUp.payload(), "Provider candidate with a mismatched capability version");
        AgentPromptContext wrongVersionContext = new AgentPromptContext("Which concrete lookup implements the declaration?",
                com.java.system.agent.answering.domain.conversation.SessionHistory.empty(), binding.runId(), binding.attemptId(),
                Map.of(new com.java.system.agent.answering.domain.handle.CapabilityHandle("implementation-capability", binding), policy),
                Map.of(wrongVersionHandle, new IssuedCandidate(wrongVersionHandle, wrongVersionFollowUp)), Map.of(), Map.of(),
                plannedInteractions, Optional.empty(),
                new com.java.system.agent.answering.domain.run.AttemptBudget(3, 0, 3, 0, 1, 0, 3, 0, 1, 0));

        assertThat(offlineRegistry.issuedRegistrations(wrongVersionContext).stream()
                .map(registration -> registration.name()).toList()).doesNotContain(policy.name());

        AgentActionProposal proposal = offlineRegistry.interpretToolCall(policy.name(), """
                {"candidateHandles":["implementation-follow-up"],"questionToResolve":"Which concrete lookup implements the declaration?","rationale":"The provider retained the declaration target"}
                """, context);
        QueryAction action = (QueryAction) ((AgentActionProposal.Proposed) proposal).action();
        CapabilityInvocation invocation = new CapabilityInvocation(policy, List.of(issuedCandidate), action.questionToResolve(),
                action.payload(), revisions);

        assertThat(action.candidates()).extracting(candidate -> candidate.value()).containsExactly(candidateHandle.value());
        assertThat(offlineRegistry.execute(invocation)).isInstanceOf(CapabilityExecutionResult.Succeeded.class);
        org.mockito.ArgumentCaptor<CapabilityExecutionContext> executorContext =
                org.mockito.ArgumentCaptor.forClass(CapabilityExecutionContext.class);
        org.mockito.ArgumentCaptor<DiscoverMethodImplementationsExecutionInput> executorInput =
                org.mockito.ArgumentCaptor.forClass(DiscoverMethodImplementationsExecutionInput.class);
        verify(offlineAdapter).discoverMethodImplementations(executorContext.capture(), executorInput.capture());
        assertThat(executorContext.getValue().expectedRevisions()).isEqualTo(revisions);
        assertThat(executorInput.getValue().target()).isEqualTo(abstractDeclaration);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "M6_SEMANTIC_BASE_URL", matches = ".+")
    void executesTheM5ReadOnlyConsumerContractAgainstThePinnedRepository() {
        initializeLiveContract();
        RepositoryId repositoryId = new RepositoryId(agentRepositoryId);
        RepositoryRevision revision = new RepositoryRevision(agentRevision);

        assertThat(adapter.availableRepositories())
                .extracting(descriptor -> descriptor.repositoryId().value())
                .contains(agentRepositoryId);
        assertThat(adapter.currentRevision(repositoryId))
                .isEqualTo(new RepositoryRevisionResult.Ready(revision));
        assertThat(adapter.listEntryPoints(repositoryContext("codebase_list_entry_points", revision),
                new ListEntryPointsExecutionInput(EntryPointType.API)))
                .isInstanceOf(CapabilityExecutionResult.Succeeded.class);
        assertThat(adapter.lookupApiRoute(repositoryContext("codebase_lookup_api_route", revision),
                new LookupApiRouteExecutionInput("/v1/repositories", null)))
                .isInstanceOf(CapabilityExecutionResult.Succeeded.class);
        assertThat(adapter.suggestApiRoute(repositoryContext("codebase_suggest_api_route", revision),
                new SuggestApiRouteExecutionInput("/v1/repositories", null, 3)))
                .isInstanceOf(CapabilityExecutionResult.Succeeded.class);
        assertThat(adapter.outgoingCallGraph(targetContext("codebase_outgoing_call_graph", revision),
                new OutgoingCallGraphExecutionInput(1, methodTargetPayload())))
                .isInstanceOf(CapabilityExecutionResult.Succeeded.class);
        assertThat(adapter.incomingCallGraph(targetContext("codebase_incoming_call_graph", revision),
                new IncomingCallGraphExecutionInput(1, methodTargetPayload())))
                .isInstanceOf(CapabilityExecutionResult.Succeeded.class);

        SemanticDtos.OutgoingCallGraphResponse response = rawOutgoingCallGraph(revision);
        assertOutgoingGraphContract(response);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "M6_SEMANTIC_BASE_URL", matches = ".+")
    void executesTheM6SemanticConsumerContractAgainstThePinnedFixtureRepository() {
        initializeLiveContract();
        RepositoryId repositoryId = new RepositoryId(discoveryRepositoryId);
        RepositoryRevision revision = new RepositoryRevision(discoveryRevision);
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, revision);
        List<String> executedCapabilities = new ArrayList<>();

        assertThat(adapter.currentRevision(repositoryId)).isEqualTo(new RepositoryRevisionResult.Ready(revision));

        CapabilityExecutionResult.Succeeded concepts = executeDiscovery(
                CodeIntelligenceQuery.DISCOVER_CONCEPTS.capabilityName(),
                repositoryCandidate(repositoryId, revisions),
                payloadCodec.encode(new DiscoverConceptsExecutionInput(
                        List.of(new DiscoverConceptsExecutionInput.Term("Order", "TOKEN_PREFIX")),
                        List.of("TYPE", "MAPPER_STATEMENT"), Optional.empty(), 0, 20)),
                revisions, executedCapabilities);
        FollowUpCandidate typeMembers = followUp(concepts, CodeIntelligenceQuery.DISCOVER_TYPE_MEMBERS, revision);
        FollowUpCandidate evidenceSource = followUp(concepts, CodeIntelligenceQuery.GET_EVIDENCE_SOURCE, revision);

        CapabilityExecutionResult.Succeeded members = executeDiscovery(
                CodeIntelligenceQuery.DISCOVER_TYPE_MEMBERS.capabilityName(),
                followUpCandidate(typeMembers, revisions), typeMembers.payload(), revisions, executedCapabilities);
        FollowUpCandidate resolveConcept = followUp(members, CodeIntelligenceQuery.RESOLVE_CONCEPT, revision);
        FollowUpCandidate internalReferences = followUp(members, CodeIntelligenceQuery.FIND_INTERNAL_REFERENCES, revision);
        assertThat(resolveConcept.payload().value()).isEqualTo(
                "{\"identity\":{\"kind\":\"TYPE\",\"sourceType\":{\"javaType\":{\"className\":\"OrderMapper\","
                        + "\"packageName\":\"com.example.m6\"},\"sourceFile\":"
                        + "\"src/main/java/com/example/m6/OrderMapper.java\"}}}");

        executeDiscovery(CodeIntelligenceQuery.RESOLVE_CONCEPT.capabilityName(),
                followUpCandidate(resolveConcept, revisions), resolveConcept.payload(), revisions, executedCapabilities);
        executeDiscovery(CodeIntelligenceQuery.FIND_INTERNAL_REFERENCES.capabilityName(),
                followUpCandidate(internalReferences, revisions), internalReferences.payload(), revisions, executedCapabilities);
        executeDiscovery(CodeIntelligenceQuery.GET_EVIDENCE_SOURCE.capabilityName(),
                followUpCandidate(evidenceSource, revisions), evidenceSource.payload(), revisions, executedCapabilities);

        executeDiscovery(CodeIntelligenceQuery.DISCOVER_EVENT_LISTENERS.capabilityName(),
                repositoryCandidate(repositoryId, revisions), payloadCodec.encode(
                        new DiscoverEventListenersExecutionInput("com.example.m6.OrderChanged", 0, 20)),
                revisions, executedCapabilities);
        SemanticTarget orderLookup = orderLookupTarget();
        executeDiscovery(CodeIntelligenceQuery.DISCOVER_METHOD_IMPLEMENTATIONS.capabilityName(),
                targetCandidate(repositoryId, revision, revisions, orderLookup, "order-lookup-interface"),
                payloadCodec.encode(new DiscoverMethodImplementationsExecutionInput(
                        new JavaSemanticCandidateTargetMapper().methodTarget(orderLookup))),
                revisions, executedCapabilities);

        CapabilityExecutionResult.Succeeded resolved = executeDiscovery(
                CodeIntelligenceQuery.RESOLVE_SOURCE_SYMBOL.capabilityName(),
                targetCandidate(repositoryId, revision, revisions, defaultLookupTarget(), "default-order-lookup"),
                payloadCodec.encode(new com.java.system.agent.codeintelligence.planning.ResolveSourceSymbolExecutionInput(
                        "findById", Optional.of(new SemanticDtos.Position(11, 18)),
                        sourceSymbolContext(defaultLookupTarget()))),
                revisions, executedCapabilities);
        FollowUpCandidate methodSource = followUp(resolved, CodeIntelligenceQuery.GET_METHOD_SOURCE, revision);
        CapabilityInvocation methodSourceInvocation = invocation(
                CodeIntelligenceQuery.GET_METHOD_SOURCE.capabilityName(), followUpCandidate(methodSource, revisions),
                methodSource.payload(), revisions);
        assertThat(methodSourceInvocation.payload()).isEqualTo(methodSource.payload());
        CapabilityExecutionResult.Succeeded methodSourceResult = execute(methodSourceInvocation, executedCapabilities);

        FollowUpCandidate sourceSegment = followUp(methodSourceResult, CodeIntelligenceQuery.GET_SOURCE_SEGMENT, revision);
        CapabilityInvocation sourceSegmentInvocation = invocation(
                CodeIntelligenceQuery.GET_SOURCE_SEGMENT.capabilityName(), followUpCandidate(sourceSegment, revisions),
                sourceSegment.payload(), revisions);
        assertThat(sourceSegmentInvocation.payload()).isEqualTo(sourceSegment.payload());
        execute(sourceSegmentInvocation, executedCapabilities);

        assertThat(executedCapabilities).containsExactlyInAnyOrder(
                CodeIntelligenceQuery.DISCOVER_CONCEPTS.capabilityName(),
                CodeIntelligenceQuery.RESOLVE_CONCEPT.capabilityName(),
                CodeIntelligenceQuery.DISCOVER_EVENT_LISTENERS.capabilityName(),
                CodeIntelligenceQuery.DISCOVER_METHOD_IMPLEMENTATIONS.capabilityName(),
                CodeIntelligenceQuery.DISCOVER_TYPE_MEMBERS.capabilityName(),
                CodeIntelligenceQuery.FIND_INTERNAL_REFERENCES.capabilityName(),
                CodeIntelligenceQuery.GET_EVIDENCE_SOURCE.capabilityName(),
                CodeIntelligenceQuery.GET_METHOD_SOURCE.capabilityName(),
                CodeIntelligenceQuery.GET_SOURCE_SEGMENT.capabilityName(),
                CodeIntelligenceQuery.RESOLVE_SOURCE_SYMBOL.capabilityName());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "M6_SEMANTIC_BASE_URL", matches = ".+")
    void mapsRepresentativeLiveFailuresToProviderNeutralResults() {
        initializeLiveContract();
        RepositoryId repositoryId = new RepositoryId(agentRepositoryId);
        JavaSemanticServiceHttpAdapter unauthorizedAdapter = new JavaSemanticServiceHttpAdapter(RestClient.builder()
                .baseUrl(requiredEnvironment("M6_SEMANTIC_BASE_URL"))
                .defaultHeader("X-Api-Token", "m5-contract-invalid-token")
                .build());

        assertThat(unauthorizedAdapter.currentRevision(repositoryId))
                .isInstanceOfSatisfying(RepositoryRevisionResult.Failed.class,
                        failed -> assertThat(failed.failure().code()).isEqualTo(RepositoryRevisionFailureCode.FORBIDDEN));
        assertThat(adapter.currentRevision(new RepositoryId(UNKNOWN_REPOSITORY_ID)))
                .isInstanceOfSatisfying(RepositoryRevisionResult.Failed.class,
                        failed -> assertThat(failed.failure().code())
                                .isEqualTo(RepositoryRevisionFailureCode.REPOSITORY_NOT_FOUND));

        RepositoryRevision wrongRevision = new RepositoryRevision(UNKNOWN_REVISION);
        assertThat(adapter.listEntryPoints(repositoryContext("codebase_list_entry_points", wrongRevision),
                new ListEntryPointsExecutionInput(EntryPointType.API)))
                .isInstanceOfSatisfying(CapabilityExecutionResult.Failed.class,
                        failed -> assertThat(failed.failure().code())
                                .isEqualTo(CapabilityExecutionFailureCode.REVISION_CONFLICT));
        SemanticDtos.ApiErrorResponse revisionError = rawRevisionConflict(wrongRevision);
        assertThat(revisionError.errorCode()).isEqualTo("REPOSITORY_REVISION_MISMATCH");
        assertThat(revisionError.expectedRevision()).isEqualTo(UNKNOWN_REVISION);
        assertThat(revisionError.currentRevision()).isEqualTo(agentRevision);

        assertThat(adapter.lookupApiRoute(repositoryContext("codebase_lookup_api_route",
                new RepositoryRevision(agentRevision)), new LookupApiRouteExecutionInput("", null)))
                .isInstanceOfSatisfying(CapabilityExecutionResult.Failed.class,
                        failed -> assertThat(failed.failure().code())
                                .isEqualTo(CapabilityExecutionFailureCode.DEPENDENCY_FAILURE));
    }

    private SemanticDtos.OutgoingCallGraphResponse rawOutgoingCallGraph(RepositoryRevision revision) {
        SemanticDtos.AnalyzeOutgoingCallGraphRequest request = new SemanticDtos.AnalyzeOutgoingCallGraphRequest(
                agentRepositoryId, revision.value(), 1, methodTargetPayload());
        SemanticDtos.OutgoingCallGraphResponse response = restClient.post()
                .uri("/v1/analyses/call-graphs/outgoing")
                .body(request)
                .retrieve()
                .body(SemanticDtos.OutgoingCallGraphResponse.class);
        return Objects.requireNonNull(response, "live outgoing graph response must not be null");
    }

    private SemanticDtos.ApiErrorResponse rawRevisionConflict(RepositoryRevision expectedRevision) {
        SemanticDtos.ApiErrorResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/v1/repositories/{repoId}/entry-points")
                        .queryParam("expectedRevision", expectedRevision.value())
                        .queryParam("types", EntryPointType.API.name())
                        .build(agentRepositoryId))
                .exchange((request, clientResponse) -> clientResponse.bodyTo(SemanticDtos.ApiErrorResponse.class));
        return Objects.requireNonNull(response, "live revision conflict response must not be null");
    }

    private void assertOutgoingGraphContract(SemanticDtos.OutgoingCallGraphResponse response) {
        assertThat(response.analyzedRevision()).isEqualTo(agentRevision);
        assertThat(response.traversal().requestedDepth()).isEqualTo(1);
        assertThat(response.traversal().expandedNodeCount()).isGreaterThanOrEqualTo(0);
        assertThat(response.traversal().nodeBudget()).isGreaterThanOrEqualTo(0);
        assertThat(StringUtils.hasText(response.traversal().limitReason())).isTrue();

        SemanticDtos.GraphNode root = response.nodes().stream()
                .filter(node -> response.rootNodeId().equals(node.nodeId()))
                .findFirst()
                .orElseThrow();
        SemanticDtos.MethodTarget rootTarget = Objects.requireNonNull(root.target(), "root target must not be null");
        assertThat(rootTarget)
                .extracting(SemanticDtos.MethodTarget::sourceFile, SemanticDtos.MethodTarget::packageName,
                        SemanticDtos.MethodTarget::className, SemanticDtos.MethodTarget::methodName,
                        SemanticDtos.MethodTarget::parameterTypes)
                .containsExactly(SOURCE_FILE, PACKAGE_NAME, CLASS_NAME, METHOD_NAME, List.of());

        for (SemanticDtos.GraphNode node : response.nodes()) {
            assertTextRangeWhenPresent(node.declarationRange());
            assertFollowUps(node.availableFollowUps());
        }
        for (SemanticDtos.GraphEdge edge : response.edges()) {
            assertSourceRange(Objects.requireNonNull(edge.callSite(), "graph call site must not be null"));
            assertFollowUps(edge.availableFollowUps());
        }
        for (SemanticDtos.GraphWarning warning : response.warnings()) {
            assertSourceRangeWhenPresent(warning.callSite());
            assertFollowUps(warning.availableFollowUps());
        }
    }

    private void assertTextRangeWhenPresent(SemanticDtos.TextRangePayload range) {
        if (Objects.nonNull(range)) {
            assertTextRange(range);
        }
    }

    private void assertSourceRangeWhenPresent(SemanticDtos.SourceRangePayload range) {
        if (Objects.nonNull(range)) {
            assertSourceRange(range);
        }
    }

    private void assertSourceRange(SemanticDtos.SourceRangePayload range) {
        assertThat(StringUtils.hasText(range.sourceFile())).isTrue();
        assertTextRange(Objects.requireNonNull(range.range(), "source range must not be null"));
    }

    private void assertTextRange(SemanticDtos.TextRangePayload range) {
        SemanticDtos.Position start = Objects.requireNonNull(range.start(), "range start must not be null");
        SemanticDtos.Position end = Objects.requireNonNull(range.end(), "range end must not be null");
        assertThat(start.line()).isGreaterThanOrEqualTo(0);
        assertThat(start.character()).isGreaterThanOrEqualTo(0);
        assertThat(end.line()).isGreaterThanOrEqualTo(start.line());
        assertThat(end.character()).isGreaterThanOrEqualTo(0);
        if (end.line().equals(start.line())) {
            assertThat(end.character()).isGreaterThanOrEqualTo(start.character());
        }
    }

    private void assertFollowUps(List<SemanticDtos.AvailableFollowUp> followUps) {
        for (SemanticDtos.AvailableFollowUp followUp : followUps) {
            assertThat(followUp.operation()).isNotBlank();
            assertThat(followUp.api().method()).isNotBlank();
            assertThat(followUp.api().path()).isNotBlank();
            assertThat(followUp.api().operationId()).isNotBlank();
            switch (followUp.request()) {
                case SemanticDtos.TargetFollowUpRequest request -> assertFollowUpScope(
                        request.repoId(), request.expectedRevision());
                case SemanticDtos.SourceSegmentFollowUpRequest request -> assertFollowUpScope(
                        request.repoId(), request.expectedRevision());
                default -> assertFollowUpScope(followUp.request().repoId(), followUp.request().expectedRevision());
            }
        }
    }

    private void assertFollowUpScope(String repositoryId, String expectedRevision) {
        assertThat(repositoryId).isEqualTo(agentRepositoryId);
        assertThat(expectedRevision).isEqualTo(agentRevision);
    }

    private CapabilityExecutionResult.Succeeded executeDiscovery(
            String capabilityName,
            IssuedCandidate candidate,
            CapabilityInputPayload payload,
            RevisionVector revisions,
            List<String> executedCapabilities) {
        return execute(invocation(capabilityName, candidate, payload, revisions), executedCapabilities);
    }

    private CapabilityExecutionResult.Succeeded execute(
            CapabilityInvocation invocation,
            List<String> executedCapabilities) {
        executedCapabilities.add(invocation.capability().name());
        CapabilityExecutionResult result = registry.execute(invocation);
        assertThat(result).isInstanceOf(CapabilityExecutionResult.Succeeded.class);
        CapabilityExecutionResult.Succeeded succeeded = (CapabilityExecutionResult.Succeeded) result;
        assertDiscoveryRevision(succeeded);
        return succeeded;
    }

    private void assertDiscoveryRevision(CapabilityExecutionResult.Succeeded result) {
        List<RepositoryRevision> observedRevisions = new ArrayList<>();
        for (AnalysisCandidate candidate : result.discoveredCandidates()) {
            assertThat(candidate.repositoryId().value()).isEqualTo(discoveryRepositoryId);
            candidate.repositoryRevision().ifPresent(observedRevisions::add);
        }
        for (EvidenceRef evidence : result.evidence()) {
            assertThat(evidence.repositoryId().value()).isEqualTo(discoveryRepositoryId);
            observedRevisions.add(evidence.repositoryRevision());
        }
        assertThat(observedRevisions).isNotEmpty().allSatisfy(
                observedRevision -> assertThat(observedRevision.value()).isEqualTo(discoveryRevision));
    }

    private CapabilityInvocation invocation(
            String capabilityName,
            IssuedCandidate candidate,
            CapabilityInputPayload payload,
            RevisionVector revisions) {
        CapabilityPolicy policy = registry.availableCapabilities().stream()
                .filter(availableCapability -> availableCapability.name().equals(capabilityName))
                .findFirst()
                .orElseThrow();
        return new CapabilityInvocation(policy, List.of(candidate), "Verify M6 semantic consumer contract", payload, revisions);
    }

    private FollowUpCandidate followUp(
            CapabilityExecutionResult.Succeeded result,
            CodeIntelligenceQuery target,
            RepositoryRevision expectedRevision) {
        FollowUpCandidate candidate = result.discoveredCandidates().stream()
                .filter(FollowUpCandidate.class::isInstance)
                .map(FollowUpCandidate.class::cast)
                .filter(followUp -> followUp.targetCapabilityName().equals(target.capabilityName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing provider-issued follow-up " + target.capabilityName()));
        assertThat(candidate.repositoryId().value()).isEqualTo(discoveryRepositoryId);
        assertThat(candidate.analyzedRevision()).isEqualTo(expectedRevision);
        assertThat(candidate.targetCapabilityVersion()).isEqualTo(target.version());
        return candidate;
    }

    private IssuedCandidate repositoryCandidate(RepositoryId repositoryId, RevisionVector revisions) {
        return new IssuedCandidate(new CandidateHandle("m6-fixture-repository",
                fixtureBinding(revisions), CandidateKind.REPOSITORY),
                new RepositoryCandidate(repositoryId, "M6 semantic contract fixture repository"));
    }

    private IssuedCandidate targetCandidate(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            RevisionVector revisions,
            SemanticTarget target,
            String handleId) {
        return new IssuedCandidate(new CandidateHandle(handleId, fixtureBinding(revisions), CandidateKind.SEMANTIC_TARGET),
                new SemanticTargetCandidate(repositoryId, revision, target, "M6 semantic contract fixture target"));
    }

    private IssuedCandidate followUpCandidate(FollowUpCandidate candidate, RevisionVector revisions) {
        return new IssuedCandidate(new CandidateHandle("m6-follow-up-" + candidate.targetCapabilityName(),
                fixtureBinding(revisions), CandidateKind.FOLLOW_UP), candidate);
    }

    private HandleBinding fixtureBinding(RevisionVector revisions) {
        return new HandleBinding(new AnalysisRunId("m6-live-run"), new AnalysisAttemptId("m6-live-attempt"), revisions);
    }

    private SemanticTarget orderLookupTarget() {
        return fixtureTarget("src/main/java/com/example/m6/OrderLookup.java", "OrderLookup", "findById");
    }

    private SemanticTarget defaultLookupTarget() {
        return fixtureTarget("src/main/java/com/example/m6/DefaultOrderLookup.java", "DefaultOrderLookup", "findById");
    }

    private SemanticDtos.SourceSymbolContextPayload sourceSymbolContext(SemanticTarget target) {
        return new JavaSemanticCandidateTargetMapper().sourceSymbolContext(target);
    }

    private SemanticTarget fixtureTarget(String sourceFile, String className, String methodName) {
        return new JavaSemanticResultMapper().semanticTarget(new SemanticDtos.MethodTarget(sourceFile,
                "com.example.m6", className, methodName, List.of("java.lang.String")));
    }

    private CapabilityExecutionContext repositoryContext(String name, RepositoryRevision revision) {
        RepositoryId repositoryId = new RepositoryId(agentRepositoryId);
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, revision);
        IssuedCandidate candidate = new IssuedCandidate(
                new CandidateHandle("live-repository-candidate", new HandleBinding(new AnalysisRunId("live-run"),
                        new AnalysisAttemptId("live-attempt"), revisions), CandidateKind.REPOSITORY),
                new RepositoryCandidate(repositoryId, "Live contract repository"));
        return new CapabilityExecutionContext(descriptor(name, CandidateKind.REPOSITORY), List.of(candidate),
                "Verify Java Semantic Service consumer contract", revisions);
    }

    private CapabilityExecutionContext targetContext(String name, RepositoryRevision revision) {
        RepositoryId repositoryId = new RepositoryId(agentRepositoryId);
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, revision);
        SemanticTarget target = new JavaSemanticResultMapper().semanticTarget(methodTarget());
        IssuedCandidate candidate = new IssuedCandidate(
                new CandidateHandle("live-target-candidate", new HandleBinding(new AnalysisRunId("live-run"),
                        new AnalysisAttemptId("live-attempt"), revisions), CandidateKind.SEMANTIC_TARGET),
                new SemanticTargetCandidate(repositoryId, revision, target, "Live contract target"));
        return new CapabilityExecutionContext(descriptor(name, CandidateKind.SEMANTIC_TARGET), List.of(candidate),
                "Verify Java Semantic Service call graph contract", revisions);
    }

    private SemanticDtos.MethodTarget methodTarget() {
        return new SemanticDtos.MethodTarget(SOURCE_FILE, PACKAGE_NAME, CLASS_NAME, METHOD_NAME, List.of());
    }

    private SemanticDtos.MethodTargetPayload methodTargetPayload() {
        SemanticDtos.JavaTypeIdentityPayload javaType = new SemanticDtos.JavaTypeIdentityPayload(
                PACKAGE_NAME, CLASS_NAME);
        SemanticDtos.SourceTypeIdentityPayload sourceType = new SemanticDtos.SourceTypeIdentityPayload(
                javaType, SOURCE_FILE);
        return new SemanticDtos.MethodTargetPayload(sourceType, METHOD_NAME, List.of());
    }

    private CapabilityPolicy descriptor(String name, CandidateKind candidateKind) {
        return new CapabilityPolicy(name, "v1", Set.of(candidateKind), 0, 1);
    }

    private void initializeLiveContract() {
        agentRepositoryId = requiredEnvironment("M6_AGENT_REPO_ID");
        agentRevision = requiredEnvironment("M6_AGENT_EXPECTED_REVISION");
        discoveryRepositoryId = requiredEnvironment("M6_DISCOVERY_REPO_ID");
        discoveryRevision = requiredEnvironment("M6_DISCOVERY_EXPECTED_REVISION");
        restClient = RestClient.builder()
                .baseUrl(requiredEnvironment("M6_SEMANTIC_BASE_URL"))
                .defaultHeader("X-Api-Token", requiredEnvironment("M6_SEMANTIC_API_TOKEN"))
                .requestInterceptor((request, body, execution) -> {
                    String wireBody = new String(body, StandardCharsets.UTF_8);
                    if ("/v1/discovery/concepts/resolve".equals(request.getURI().getPath())) {
                        assertThat(wireBody).isEqualTo(
                                "{\"repoId\":\"m6-semantic-contract\",\"expectedRevision\":\"FIXTURE\","
                                        + "\"identity\":{\"kind\":\"TYPE\",\"sourceType\":{\"javaType\":{"
                                        + "\"packageName\":\"com.example.m6\",\"className\":\"OrderMapper\"},"
                                        + "\"sourceFile\":\"src/main/java/com/example/m6/OrderMapper.java\"}}}");
                    } else if ("/v1/discovery/internal-references".equals(request.getURI().getPath())) {
                        assertThat(wireBody).doesNotContain("\"depth\"");
                    } else if ("/v1/discovery/evidence-source".equals(request.getURI().getPath())) {
                        assertThat(wireBody).doesNotContain("\"fragmentIdentity\"");
                    }
                    return execution.execute(request, body);
                })
                .build();
        adapter = new JavaSemanticServiceHttpAdapter(restClient);
        payloadCodec = new CanonicalCapabilityPayloadCodec(Validation.buildDefaultValidatorFactory().getValidator());
        registry = new PlanningToolRegistry(List.of(new CodeIntelligencePlanningToolProvider(adapter, payloadCodec)),
                new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator()), payloadCodec);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        Assert.state(StringUtils.hasText(value), () -> name + " must be set for the live semantic contract");
        return value;
    }
}
