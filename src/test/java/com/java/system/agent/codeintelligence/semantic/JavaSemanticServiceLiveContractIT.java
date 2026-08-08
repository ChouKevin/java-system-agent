package com.java.system.agent.codeintelligence.semantic;

import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.candidate.FollowUpCandidate;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.candidate.AnalysisCandidate;
import com.java.system.agent.answering.domain.candidate.RepositoryCandidate;
import com.java.system.agent.answering.domain.candidate.SemanticTargetCandidate;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.evidence.EvidenceRef;
import com.java.system.agent.answering.domain.evidence.SemanticTarget;
import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.out.CapabilityExecutionFailureCode;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.answering.port.out.RepositoryRevisionFailureCode;
import com.java.system.agent.answering.port.out.RepositoryRevisionResult;
import com.java.system.agent.answering.port.out.CapabilityInvocation;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 驗證 Agent 對 Java Semantic Service 的 live HTTP consumer contract
 */
@EnabledIfEnvironmentVariable(named = "M6_SEMANTIC_BASE_URL", matches = ".+")
class JavaSemanticServiceLiveContractIT {

    private static final String AGENT_REPOSITORY_ID = requiredEnvironment("M6_AGENT_REPO_ID");
    private static final String AGENT_REVISION = requiredEnvironment("M6_AGENT_EXPECTED_REVISION");
    private static final String DISCOVERY_REPOSITORY_ID = requiredEnvironment("M6_DISCOVERY_REPO_ID");
    private static final String DISCOVERY_REVISION = requiredEnvironment("M6_DISCOVERY_EXPECTED_REVISION");
    private static final String SOURCE_FILE =
            "src/main/java/com/java/system/agent/codeintelligence/semantic/JavaSemanticServiceHttpAdapter.java";
    private static final String PACKAGE_NAME = "com.java.system.agent.codeintelligence.semantic";
    private static final String CLASS_NAME = "JavaSemanticServiceHttpAdapter";
    private static final String METHOD_NAME = "availableRepositories";
    private static final String UNKNOWN_REPOSITORY_ID = "m5-contract-missing-repository";
    private static final String UNKNOWN_REVISION = "0000000000000000000000000000000000000000";

    private final RestClient restClient = RestClient.builder()
            .baseUrl(requiredEnvironment("M6_SEMANTIC_BASE_URL"))
            .defaultHeader("X-Api-Token", requiredEnvironment("M6_SEMANTIC_API_TOKEN"))
            .build();
    private final JavaSemanticServiceHttpAdapter adapter = new JavaSemanticServiceHttpAdapter(restClient);
    private final CanonicalCapabilityPayloadCodec payloadCodec = new CanonicalCapabilityPayloadCodec(
            Validation.buildDefaultValidatorFactory().getValidator());
    private final PlanningToolRegistry registry = new PlanningToolRegistry(
            List.of(new CodeIntelligencePlanningToolProvider(adapter, payloadCodec)),
            new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator()), payloadCodec);

    @Test
    void executesTheM5ReadOnlyConsumerContractAgainstThePinnedRepository() {
        RepositoryId repositoryId = new RepositoryId(AGENT_REPOSITORY_ID);
        RepositoryRevision revision = new RepositoryRevision(AGENT_REVISION);

        assertThat(adapter.availableRepositories())
                .extracting(descriptor -> descriptor.repositoryId().value())
                .contains(AGENT_REPOSITORY_ID);
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
                new OutgoingCallGraphExecutionInput(1)))
                .isInstanceOf(CapabilityExecutionResult.Succeeded.class);
        assertThat(adapter.incomingCallGraph(targetContext("codebase_incoming_call_graph", revision),
                new IncomingCallGraphExecutionInput(1)))
                .isInstanceOf(CapabilityExecutionResult.Succeeded.class);

        SemanticDtos.OutgoingCallGraphResponse response = rawOutgoingCallGraph(revision);
        assertOutgoingGraphContract(response);
    }

    @Test
    void executesTheM6SemanticConsumerContractAgainstThePinnedFixtureRepository() {
        RepositoryId repositoryId = new RepositoryId(DISCOVERY_REPOSITORY_ID);
        RepositoryRevision revision = new RepositoryRevision(DISCOVERY_REVISION);
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, revision);
        List<String> executedCapabilities = new ArrayList<>();

        assertThat(adapter.currentRevision(repositoryId)).isEqualTo(new RepositoryRevisionResult.Ready(revision));

        CapabilityExecutionResult.Succeeded concepts = executeDiscovery(
                CodeIntelligenceQuery.DISCOVER_CONCEPTS.capabilityName(),
                repositoryCandidate(repositoryId, revisions),
                payloadCodec.encode(new DiscoverConceptsExecutionInput(
                        List.of(new DiscoverConceptsExecutionInput.Term("Default", "TOKEN_PREFIX")),
                        List.of("TYPE", "MAPPER_STATEMENT"), Optional.of("com.example.m6"), 0, 20)),
                revisions, executedCapabilities);
        FollowUpCandidate typeMembers = followUp(concepts, CodeIntelligenceQuery.DISCOVER_TYPE_MEMBERS, revision);
        FollowUpCandidate evidenceSource = followUp(concepts, CodeIntelligenceQuery.GET_EVIDENCE_SOURCE, revision);

        CapabilityExecutionResult.Succeeded members = executeDiscovery(
                CodeIntelligenceQuery.DISCOVER_TYPE_MEMBERS.capabilityName(),
                followUpCandidate(typeMembers, revisions), typeMembers.payload(), revisions, executedCapabilities);
        FollowUpCandidate resolveConcept = followUp(members, CodeIntelligenceQuery.RESOLVE_CONCEPT, revision);
        FollowUpCandidate internalReferences = followUp(members, CodeIntelligenceQuery.FIND_INTERNAL_REFERENCES, revision);

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
        executeDiscovery(CodeIntelligenceQuery.DISCOVER_METHOD_IMPLEMENTATIONS.capabilityName(),
                targetCandidate(repositoryId, revision, revisions, orderLookupTarget(), "order-lookup-interface"),
                payloadCodec.encode(new DiscoverMethodImplementationsExecutionInput(Optional.empty())),
                revisions, executedCapabilities);

        CapabilityExecutionResult.Succeeded resolved = executeDiscovery(
                CodeIntelligenceQuery.RESOLVE_SOURCE_SYMBOL.capabilityName(),
                targetCandidate(repositoryId, revision, revisions, defaultLookupTarget(), "default-order-lookup"),
                payloadCodec.encode(new com.java.system.agent.codeintelligence.planning.ResolveSourceSymbolExecutionInput(
                        "findById", Optional.empty(), Optional.empty())),
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
    void mapsRepresentativeLiveFailuresToProviderNeutralResults() {
        RepositoryId repositoryId = new RepositoryId(AGENT_REPOSITORY_ID);
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
        assertThat(revisionError.currentRevision()).isEqualTo(AGENT_REVISION);

        assertThat(adapter.lookupApiRoute(repositoryContext("codebase_lookup_api_route",
                new RepositoryRevision(AGENT_REVISION)), new LookupApiRouteExecutionInput("", null)))
                .isInstanceOfSatisfying(CapabilityExecutionResult.Failed.class,
                        failed -> assertThat(failed.failure().code())
                                .isEqualTo(CapabilityExecutionFailureCode.DEPENDENCY_FAILURE));
    }

    private SemanticDtos.OutgoingCallGraphResponse rawOutgoingCallGraph(RepositoryRevision revision) {
        SemanticDtos.AnalyzeOutgoingCallGraphRequest request = new SemanticDtos.AnalyzeOutgoingCallGraphRequest(
                AGENT_REPOSITORY_ID, revision.value(), 1, methodTargetPayload());
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
                        .build(AGENT_REPOSITORY_ID))
                .exchange((request, clientResponse) -> clientResponse.bodyTo(SemanticDtos.ApiErrorResponse.class));
        return Objects.requireNonNull(response, "live revision conflict response must not be null");
    }

    private void assertOutgoingGraphContract(SemanticDtos.OutgoingCallGraphResponse response) {
        assertThat(response.analyzedRevision()).isEqualTo(AGENT_REVISION);
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
        assertThat(repositoryId).isEqualTo(AGENT_REPOSITORY_ID);
        assertThat(expectedRevision).isEqualTo(AGENT_REVISION);
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
            assertThat(candidate.repositoryId().value()).isEqualTo(DISCOVERY_REPOSITORY_ID);
            candidate.repositoryRevision().ifPresent(observedRevisions::add);
        }
        for (EvidenceRef evidence : result.evidence()) {
            assertThat(evidence.repositoryId().value()).isEqualTo(DISCOVERY_REPOSITORY_ID);
            observedRevisions.add(evidence.repositoryRevision());
        }
        assertThat(observedRevisions).isNotEmpty().allSatisfy(
                observedRevision -> assertThat(observedRevision.value()).isEqualTo(DISCOVERY_REVISION));
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
        assertThat(candidate.repositoryId().value()).isEqualTo(DISCOVERY_REPOSITORY_ID);
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

    private SemanticTarget fixtureTarget(String sourceFile, String className, String methodName) {
        return new JavaSemanticResultMapper().semanticTarget(new SemanticDtos.MethodTarget(sourceFile,
                "com.example.m6", className, methodName, List.of("java.lang.String")));
    }

    private CapabilityExecutionContext repositoryContext(String name, RepositoryRevision revision) {
        RepositoryId repositoryId = new RepositoryId(AGENT_REPOSITORY_ID);
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, revision);
        IssuedCandidate candidate = new IssuedCandidate(
                new CandidateHandle("live-repository-candidate", new HandleBinding(new AnalysisRunId("live-run"),
                        new AnalysisAttemptId("live-attempt"), revisions), CandidateKind.REPOSITORY),
                new RepositoryCandidate(repositoryId, "Live contract repository"));
        return new CapabilityExecutionContext(descriptor(name, CandidateKind.REPOSITORY), List.of(candidate),
                "Verify Java Semantic Service consumer contract", revisions);
    }

    private CapabilityExecutionContext targetContext(String name, RepositoryRevision revision) {
        RepositoryId repositoryId = new RepositoryId(AGENT_REPOSITORY_ID);
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

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        Assert.state(StringUtils.hasText(value), () -> name + " must be set for the live semantic contract");
        return value;
    }
}
