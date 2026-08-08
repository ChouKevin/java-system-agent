package com.java.system.agent.codeintelligence.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import com.java.system.agent.answering.domain.candidate.RouteCandidate;
import com.java.system.agent.answering.domain.candidate.SemanticTargetCandidate;
import com.java.system.agent.answering.domain.evidence.EvidenceRef;
import com.java.system.agent.answering.domain.evidence.SemanticTarget;
import com.java.system.agent.answering.domain.evidence.SemanticTargetKind;
import com.java.system.agent.answering.domain.observation.ObservationCode;
import com.java.system.agent.answering.port.out.CapabilityExecutionContractException;
import com.java.system.agent.answering.port.out.CapabilityExecutionFailureCode;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Java Semantic Service 成功與 error DTO 的 deterministic answering mapping 測試
 */
class JavaSemanticResultMapperTest {

    private static final String REVISION = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void projectsDiscoveryConceptResponseDecodedFromTheProviderWireContract() throws Exception {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.DiscoverConceptsResponse response = new ObjectMapper().findAndRegisterModules().readValue("""
                {"repoId":"orders","analyzedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","normalizedTerms":["order"],"searchedKinds":["TYPE"],"supportedKinds":["TYPE"],"limitations":[],"candidates":[],"page":{"offset":0,"limit":10,"returnedCount":0,"totalCount":0,"hasMore":false},"coverage":{"status":"COMPLETE","scannedFileCount":1,"extractedFileCount":1,"syntaxFailedFileCount":0},"issueSummaries":[],"availableFollowUps":[],"unavailableFollowUps":[]}
                """, SemanticDtos.DiscoverConceptsResponse.class);

        CapabilityExecutionResult.Succeeded result = (CapabilityExecutionResult.Succeeded) mapper.discoverConcepts(response);

        assertThat(result.discoveredCandidates()).isEmpty();
    }

    @Test
    void decodesAndProjectsEachDiscoverySuccessEndpointFromExactProviderJson() throws Exception {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        String sourceType = """
                {"javaType":{"packageName":"com.example","className":"Orders"},"sourceFile":"src/Orders.java"}
                """;
        String target = """
                {"sourceType":%s,"methodName":"find","parameterTypes":[]}
                """.formatted(sourceType);
        String range = """
                {"start":{"line":0,"character":0},"end":{"line":0,"character":1}}
                """;
        String location = """
                {"sourceFile":"src/Orders.java","range":%s}
                """.formatted(range);
        String page = """
                {"offset":0,"limit":10,"returnedCount":1,"totalCount":1,"hasMore":false}
                """;
        String memberPage = """
                {"offset":0,"limit":10,"returnedCount":2,"totalCount":2,"hasMore":false}
                """;
        String segment = """
                {"location":%s,"content":"class Orders {}"}
                """.formatted(location);
        String followUp = """
                {"operation":"GET_METHOD_SOURCE","api":{"method":"POST","path":"/v1/discovery/method-source","operationId":"getMethodSource"},"request":{"repoId":"orders","expectedRevision":"%s","target":%s}}
                """.formatted(REVISION, target);
        String followUps = "[" + followUp + "]";
        String concept = """
                {"identity":{"kind":"METHOD","target":%s},"displayValue":"Orders.find","matchedTerms":["order"],"authority":"SYNTAX_RESOLVED","details":{"kind":"FIELD","declaredType":{"kind":"PRIMITIVE","writtenType":"int"}},"evidence":[{"identity":{"kind":"METHOD","target":%s}}],"availableFollowUps":%s}
                """.formatted(target, target, followUps);

        SemanticDtos.DiscoverConceptsResponse concepts = objectMapper.readValue("""
                {"repoId":"orders","analyzedRevision":"%s","normalizedTerms":["order"],"searchedKinds":["METHOD"],"supportedKinds":["METHOD"],"limitations":[],"candidates":[%s],"page":%s,"coverage":{"status":"COMPLETE","scannedFileCount":1,"extractedFileCount":1,"syntaxFailedFileCount":0},"issueSummaries":[],"availableFollowUps":[],"unavailableFollowUps":[]}
                """.formatted(REVISION, concept, page), SemanticDtos.DiscoverConceptsResponse.class);
        SemanticDtos.ResolveConceptResponse resolvedConcept = objectMapper.readValue("""
                {"repoId":"orders","analyzedRevision":"%s","candidate":%s}
                """.formatted(REVISION, concept), SemanticDtos.ResolveConceptResponse.class);
        SemanticDtos.DiscoverEventListenersResponse listeners = objectMapper.readValue("""
                {"repoId":"orders","analyzedRevision":"%s","requestedEventType":"com.example.Event","candidates":[{"target":%s,"listenerAnnotations":[{"kind":"EVENT_LISTENER","matchKind":"RESOLVED_IDENTITY"}],"sourceRange":%s,"availableFollowUps":%s}],"page":%s,"observationSummaries":[],"availableFollowUps":[]}
                """.formatted(REVISION, target, range, followUps, page), SemanticDtos.DiscoverEventListenersResponse.class);
        SemanticDtos.DiscoverMethodImplementationsResponse implementations = objectMapper.readValue("""
                {"repoId":"orders","revision":"%s","requestedTarget":%s,"candidates":[{"target":%s,"primary":true,"qualifiers":[],"profiles":[],"availableFollowUps":%s}],"limits":{"limit":10,"returnedCount":1,"totalCount":1,"truncated":false},"resolution":{"status":"COMPLETE","issueSummaries":[]}}
                """.formatted(REVISION, target, target, followUps), SemanticDtos.DiscoverMethodImplementationsResponse.class);
        SemanticDtos.DiscoverTypeMembersResponse members = objectMapper.readValue("""
                {"repoId":"orders","analyzedRevision":"%s","sourceType":%s,"typeKind":"CLASS","annotations":[],"implementedTypes":[],"extendedTypes":[],"members":[{"kind":"METHOD","target":%s,"availableFollowUps":%s},{"kind":"FIELD","identity":{"scope":"TYPE","ownerType":%s,"name":"id"},"writtenType":"long","resolvedType":"long","annotations":[],"limitations":[],"availableFollowUps":%s}],"page":%s,"coverage":{"status":"COMPLETE","scannedFileCount":1,"extractedFileCount":1,"syntaxFailedFileCount":0},"availableFollowUps":[]}
                """.formatted(REVISION, sourceType, target, followUps, sourceType, followUps, memberPage),
                SemanticDtos.DiscoverTypeMembersResponse.class);
        SemanticDtos.FindInternalReferencesResponse references = objectMapper.readValue("""
                {"repoId":"orders","analyzedRevision":"%s","status":"COMPLETE","targetDeclaration":{"target":{"kind":"TYPE","identity":%s},"declarationRange":%s,"availableFollowUps":%s},"totalReferenceCount":1,"referenceGroups":[{"context":{"kind":"TYPE","sourceType":%s},"representativeReferences":[{"range":%s,"availableFollowUps":%s}],"limits":{"limit":10,"returnedCount":1,"totalCount":1,"truncated":false},"availableFollowUps":%s,"unavailableFollowUps":[{"reason":"SEARCH_INCOMPLETE","recommendedAction":"FIX_SOURCE_OR_RETRY"}]},{"context":{"kind":"METHOD","method":%s},"representativeReferences":[],"limits":{"limit":10,"returnedCount":0,"totalCount":0,"truncated":false},"availableFollowUps":[],"unavailableFollowUps":[]}],"page":{"offset":0,"limit":10,"returnedCount":1,"totalCount":1,"hasMore":false},"issueSummaries":[],"availableFollowUps":[]}
                """.formatted(REVISION, sourceType, range, followUps, sourceType, range, followUps, followUps, target),
                SemanticDtos.FindInternalReferencesResponse.class);
        SemanticDtos.EvidenceSourceResponse evidence = objectMapper.readValue("""
                {"repoId":"orders","analyzedRevision":"%s","identity":{"kind":"MAPPER_STATEMENT","statementIdentity":{"statementKey":{"namespace":"orders","statementId":"find"},"resourcePath":"src/OrdersMapper.xml","documentOrdinal":0,"representation":"MAPPER_XML_ELEMENT"}},"location":%s,"segment":%s,"availableFollowUps":%s}
                """.formatted(REVISION, location, segment, followUps), SemanticDtos.EvidenceSourceResponse.class);
        SemanticDtos.MethodSourceResponse methodSource = objectMapper.readValue("""
                {"repoId":"orders","analyzedRevision":"%s","declarationLocation":%s,"segment":%s,"availableFollowUps":%s}
                """.formatted(REVISION, location, segment, followUps), SemanticDtos.MethodSourceResponse.class);
        SemanticDtos.SourceSegmentResponse sourceSegment = objectMapper.readValue("""
                {"repoId":"orders","analyzedRevision":"%s","segment":%s,"contextTruncated":false,"availableFollowUps":%s}
                """.formatted(REVISION, segment, followUps), SemanticDtos.SourceSegmentResponse.class);
        SemanticDtos.ResolveSourceSymbolResponse sourceSymbols = objectMapper.readValue("""
                {"repoId":"orders","analyzedRevision":"%s","status":"RESOLVED","contextCandidates":[{"kind":"SOURCE_TYPE","sourceFile":"src/Orders.java","retry":%s},{"kind":"METHOD","target":%s,"retry":%s}],"contextCandidateLimits":{"limit":10,"returnedCount":2,"totalCount":2,"truncated":false},"candidates":[{"kind":"METHOD","target":%s,"declarationRange":%s,"representativeOccurrence":%s,"occurrenceCount":1,"availableFollowUps":%s},{"kind":"FIELD","identity":{"scope":"TYPE","ownerType":%s,"name":"id"},"declaredType":{"writtenType":"long"},"declarationRange":%s,"representativeOccurrence":%s,"occurrenceCount":1,"availableFollowUps":%s},{"kind":"STATIC_CONSTANT","identity":{"scope":"TYPE","ownerType":%s,"name":"MAX"},"declaredType":{"writtenType":"int"},"initializerSource":"1","declarationRange":%s,"representativeOccurrence":%s,"occurrenceCount":1,"availableFollowUps":%s},{"kind":"SOURCE_TYPE","identity":%s,"declarationRange":%s,"representativeOccurrence":%s,"occurrenceCount":1,"availableFollowUps":%s}],"issues":[]}
                """.formatted(REVISION, followUp, target, followUp, target, range, range, followUps, sourceType, range, range, followUps,
                        sourceType, range, range, followUps, sourceType, range, range, followUps),
                SemanticDtos.ResolveSourceSymbolResponse.class);

        assertThat(((CapabilityExecutionResult.Succeeded) mapper.discoverConcepts(concepts)).discoveredCandidates()).hasSize(3);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.resolveConcept(resolvedConcept)).discoveredCandidates()).hasSize(3);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.discoverEventListeners(listeners)).discoveredCandidates()).hasSize(2);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.discoverMethodImplementations(implementations)).discoveredCandidates()).hasSize(2);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.discoverTypeMembers(members)).discoveredCandidates()).hasSize(3);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.findInternalReferences(references)).discoveredCandidates()).hasSize(3);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.getEvidenceSource(evidence)).evidence()).hasSize(1);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.getMethodSource(methodSource)).evidence()).hasSize(1);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.getSourceSegment(sourceSegment)).evidence()).hasSize(1);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.resolveSourceSymbol(sourceSymbols)).discoveredCandidates()).hasSize(7);
    }

    @Test
    void decodesEveryFieldTypeReferenceWireVariant() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        List<String> payloads = List.of(
                "{\"kind\":\"NAMED\",\"writtenType\":\"Orders\",\"simpleTypeName\":\"Orders\",\"sourceDefined\":true}",
                "{\"kind\":\"PARAMETERIZED\",\"writtenType\":\"List<Orders>\",\"rawType\":{\"kind\":\"NAMED\",\"writtenType\":\"List\",\"simpleTypeName\":\"List\",\"sourceDefined\":false},\"typeArguments\":[]}",
                "{\"kind\":\"PRIMITIVE\",\"writtenType\":\"int\"}",
                "{\"kind\":\"ARRAY\",\"writtenType\":\"int[]\",\"elementType\":{\"kind\":\"PRIMITIVE\",\"writtenType\":\"int\"},\"dimensions\":1}",
                "{\"kind\":\"WILDCARD\",\"writtenType\":\"?\",\"sourceDefined\":false}",
                "{\"kind\":\"TYPE_VARIABLE\",\"writtenType\":\"T\",\"variableName\":\"T\",\"upperBounds\":[],\"sourceDefined\":true}");

        for (String payload : payloads) {
            SemanticDtos.FieldTypeReferenceResponse decoded = objectMapper.readValue(payload,
                    SemanticDtos.FieldTypeReferenceResponse.class);
            assertThat(decoded.writtenType()).isNotBlank();
        }
    }

    @Test
    void projectsTheRemainingDiscoveryResponseFamiliesWithTypedTargetsAndSourceEvidence() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.MethodTargetPayload target = methodTargetPayload();
        SemanticDtos.PageResponse page = new SemanticDtos.PageResponse(0, 10, 0, 0, false);
        SemanticDtos.ConceptCoverageResponse coverage = new SemanticDtos.ConceptCoverageResponse("COMPLETE", 1, 1, 0);
        SemanticDtos.SourceRangePayload location = sourceRange();
        SemanticDtos.SourceSegmentPayload segment = new SemanticDtos.SourceSegmentPayload(location, "class Orders {}",
                Optional.empty());

        assertThat(((CapabilityExecutionResult.Succeeded) mapper.resolveConcept(
                new SemanticDtos.ResolveConceptResponse("orders", REVISION, conceptCandidate()))).discoveredCandidates()).hasSize(1);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.discoverEventListeners(
                new SemanticDtos.DiscoverEventListenersResponse("orders", REVISION, "com.example.Event", List.of(
                        new SemanticDtos.EventListenerCandidateResponse(target, List.of(), location.range(), List.of())), page, List.of(), List.of())))
                .discoveredCandidates()).hasSize(1);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.discoverMethodImplementations(
                new SemanticDtos.DiscoverMethodImplementationsResponse("orders", REVISION, target, List.of(
                        new SemanticDtos.MethodImplementationCandidateResponse(target, true, List.of(), List.of(), List.of())),
                        new SemanticDtos.BoundedResultResponse(10, 1, 1, false),
                        new SemanticDtos.MethodImplementationResolutionResponse("COMPLETE", List.of())))).discoveredCandidates()).hasSize(1);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.discoverTypeMembers(
                new SemanticDtos.DiscoverTypeMembersResponse("orders", REVISION, target.sourceType(), "CLASS", List.of(),
                        List.of(), List.of(), List.of(new SemanticDtos.MethodTypeMemberResponse("METHOD", target, List.of())),
                        page, coverage, List.of())))
                .discoveredCandidates()).hasSize(1);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.findInternalReferences(
                new SemanticDtos.FindInternalReferencesResponse("orders", REVISION, "COMPLETE",
                        new SemanticDtos.InternalReferenceTargetDeclarationResponse(
                        new SemanticDtos.InternalReferenceFollowUpTarget("TYPE", target.sourceType()), location.range(), List.of()),
                        0, List.of(new SemanticDtos.ReferenceGroupResponse(new SemanticDtos.InternalReferenceTypeContextResponse("TYPE",
                                target.sourceType()), List.of(), new SemanticDtos.BoundedResultResponse(10, 0, 0, false),
                                List.of(), List.of())), page, List.of(), List.of()))).discoveredCandidates()).isEmpty();
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.getMethodSource(
                new SemanticDtos.MethodSourceResponse("orders", REVISION, location, segment, List.of()))).evidence())
                .extracting(EvidenceRef::artifactRef).containsExactly(JavaSemanticArtifactDigest.fromContent("class Orders {}"));
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.getSourceSegment(
                new SemanticDtos.SourceSegmentResponse("orders", REVISION, segment, false, List.of()))).evidence()).hasSize(1);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.getEvidenceSource(
                new SemanticDtos.EvidenceSourceResponse("orders", REVISION, evidenceIdentity(), location, segment, List.of()))).evidence())
                .hasSize(1);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.resolveSourceSymbol(
                new SemanticDtos.ResolveSourceSymbolResponse("orders", REVISION, "RESOLVED", List.of(),
                        new SemanticDtos.BoundedResultResponse(10, 0, 0, false), List.of(
                        new SemanticDtos.MethodSourceSymbolCandidateResponse("METHOD", target, location.range(),
                                location.range(), 1, List.of())), List.of()))).discoveredCandidates()).hasSize(1);
    }

    @Test
    void rejectsUnknownDiscoveryStatusAndMalformedProviderJson() throws Exception {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();

        assertThatThrownBy(() -> mapper.resolveSourceSymbol(new SemanticDtos.ResolveSourceSymbolResponse("orders", REVISION,
                "OTHER", List.of(), new SemanticDtos.BoundedResultResponse(10, 0, 0, false), List.of(), List.of()))
                ).isInstanceOf(CapabilityExecutionContractException.class);
        assertThatThrownBy(() -> new ObjectMapper().findAndRegisterModules().readValue("""
                {"repoId":"orders","analyzedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","normalizedTerms":[],"searchedKinds":[],"supportedKinds":[],"limitations":[],"candidates":[],"page":{"offset":0,"limit":1,"returnedCount":0,"totalCount":0,"hasMore":false},"coverage":{"status":"COMPLETE","scannedFileCount":0,"extractedFileCount":0,"syntaxFailedFileCount":0},"issueSummaries":[],"availableFollowUps":[],"unavailableFollowUps":[],"unknown":true}
                """, SemanticDtos.DiscoverConceptsResponse.class)).isInstanceOf(Exception.class);
    }

    @Test
    void preservesProviderOrderAndConvertsAmbiguityAndTruncationToObservations() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.MethodTarget first = methodTarget("FirstService", "first");
        SemanticDtos.MethodTarget second = methodTarget("SecondService", "second");
        SemanticDtos.ApiRouteCandidatesResponse response = new SemanticDtos.ApiRouteCandidatesResponse(List.of(
                new SemanticDtos.ApiRouteCandidateResponse("orders", REVISION, "GET", "/orders/{id}",
                        "com.example", "OrderController", "get", new SemanticDtos.MethodTargetResolutionResponse(
                        "AMBIGUOUS", null, List.of(first, second), "multiple bindings"), List.of("TEMPLATE_MATCH"))),
                List.of(new SemanticDtos.ApiRouteObservationResponse("TRUNCATED_CANDIDATES", "more\nresults")));

        CapabilityExecutionResult.Succeeded result = (CapabilityExecutionResult.Succeeded) mapper.apiRoutes(response);

        assertThat(result.discoveredCandidates()).hasSize(3);
        assertThat(result.discoveredCandidates().get(0)).isInstanceOf(RouteCandidate.class);
        assertThat(result.discoveredCandidates().subList(1, 3)).allMatch(SemanticTargetCandidate.class::isInstance);
        assertThat(result.observations()).extracting(observation -> observation.code())
                .containsExactly(ObservationCode.AMBIGUOUS_SEMANTIC_TARGET, ObservationCode.TRUNCATED_CANDIDATES);
        assertThat(result.observations().get(1).description()).isEqualTo("more results");
    }

    @Test
    void mapsGraphToDeterministicEvidenceWithLowercaseUtf8Sha256Digest() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.MethodTarget target = methodTarget("OrderService", "find");
        SemanticDtos.OutgoingCallGraphResponse response = new SemanticDtos.OutgoingCallGraphResponse("PARTIAL", REVISION,
                "root", new SemanticDtos.GraphTraversal(1, 1, 3, true, "NODE_BUDGET"), List.of(
                new SemanticDtos.GraphNode("root", target, null, "FULL_SOURCE", "EXPANDED", "SYNCHRONOUS", null, List.of())),
                List.of(), List.of(new SemanticDtos.GraphWarning("NODE_BUDGET_REACHED", "limit\nreached",
                "root", null, null, List.of(), List.of())), List.of());

        CapabilityExecutionResult.Succeeded result = (CapabilityExecutionResult.Succeeded) mapper.outgoingCallGraph(
                JavaSemanticServiceHttpAdapterTestHelper.targetInvocation(), response);

        EvidenceRef evidence = result.evidence().getFirst();
        assertThat(evidence.content()).contains(
                "root=root; traversal=1/1/3/true/NODE_BUDGET; node=root:FULL_SOURCE:EXPANDED:SYNCHRONOUS")
                .contains("warning=NODE_BUDGET_REACHED:limit reached");
        assertThat(evidence.artifactRef()).isEqualTo(JavaSemanticArtifactDigest.fromContent(evidence.content()));
        assertThat(evidence.artifactRef().digest()).matches("[0-9a-f]{64}");
        assertThat(result.observations()).extracting(observation -> observation.code())
                .containsExactly(ObservationCode.PARTIAL_GRAPH, ObservationCode.PARTIAL_GRAPH);
    }

    @Test
    void mapsWellFormedExternalErrorsAndSeparatesProtocolContractViolation() {
        JavaSemanticErrorMapper mapper = new JavaSemanticErrorMapper(new JavaSemanticResultMapper());
        SemanticDtos.ApiErrorResponse timeout = new SemanticDtos.ApiErrorResponse("SEMANTIC_REQUEST_TIMEOUT",
                "service\ntimeout", "orders", REVISION, null, null, List.of(), "request-1");
        SemanticDtos.ApiErrorResponse protocol = new SemanticDtos.ApiErrorResponse("SEMANTIC_PROTOCOL_ERROR",
                "bad schema", "orders", REVISION, null, null, List.of(), "request-2");

        CapabilityExecutionResult.Failed result = (CapabilityExecutionResult.Failed) mapper.capability(timeout,
                "java-semantic-service:POST /v1/api-routes/lookup");

        assertThat(result.failure().code()).isEqualTo(CapabilityExecutionFailureCode.TIMEOUT);
        assertThat(result.failure().description()).isEqualTo("service timeout");
        assertThatThrownBy(() -> mapper.capability(protocol, "operation"))
                .isInstanceOf(CapabilityExecutionContractException.class);
    }

    @Test
    void rejectsImpossibleSuccessfulDtoInsteadOfPublishingTrustedOutput() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.ApiRouteCandidatesResponse response = new SemanticDtos.ApiRouteCandidatesResponse(null, List.of());

        assertThatThrownBy(() -> mapper.apiRoutes(response))
                .isInstanceOf(CapabilityExecutionContractException.class)
                .hasMessageContaining("candidate list");
    }

    @Test
    void roundTripsMethodTargetComponentsThatContainTheFormerDelimiter() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.MethodTarget expected = new SemanticDtos.MethodTarget("src/a|b/Order.java", "com.example",
                "OrderService", "find", List.of("java.lang.String|custom", "int"));

        SemanticDtos.MethodTarget actual = mapper.methodTarget(mapper.semanticTarget(expected));

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void rejectsMalformedNestedProviderValuesAsContractViolations() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.ApiRouteCandidatesResponse response = new SemanticDtos.ApiRouteCandidatesResponse(List.of(
                new SemanticDtos.ApiRouteCandidateResponse("orders", REVISION, "GET", "/orders", "com.example",
                        "OrderController", "get", new SemanticDtos.MethodTargetResolutionResponse("RESOLVED", null,
                        List.of(), "resolved"), List.of("TEMPLATE_MATCH"))), List.of());
        JavaSemanticErrorMapper errorMapper = new JavaSemanticErrorMapper(mapper);
        SemanticDtos.ApiErrorResponse malformedError = new SemanticDtos.ApiErrorResponse("SEMANTIC_REQUEST_TIMEOUT",
                "timeout", "orders", REVISION, null, null, null, "request");

        assertThatThrownBy(() -> mapper.apiRoutes(response))
                .isInstanceOf(CapabilityExecutionContractException.class);
        assertThatThrownBy(() -> errorMapper.capability(malformedError, "operation"))
                .isInstanceOf(CapabilityExecutionContractException.class);
    }

    @Test
    void usesTheUniqueResponseRootTargetAndPreservesCompleteGraphEvidence() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.MethodTarget requested = methodTarget("RequestedService", "find");
        SemanticDtos.MethodTarget responseRoot = methodTarget("ResponseService", "load");
        String longMessage = "x".repeat(1_001);
        SemanticDtos.OutgoingCallGraphResponse response = new SemanticDtos.OutgoingCallGraphResponse("PARTIAL", REVISION,
                "root", new SemanticDtos.GraphTraversal(1, 0, 0, true, "NONE"), List.of(
                new SemanticDtos.GraphNode("root", responseRoot, null, "FULL_SOURCE", "EXPANDED", "SYNCHRONOUS", null, List.of())),
                List.of(new SemanticDtos.GraphEdge("root", "opaque", sourceRange(), "opaque call",
                "MYBATIS_MAPPER", "RESOLVED_OPAQUE", List.of("proof"))), List.of(
                new SemanticDtos.GraphWarning("NODE_BUDGET_REACHED", longMessage, "root", null, null, List.of(), List.of())), List.of(
                new SemanticDtos.GraphError("CHILD_SEMANTIC_QUERY_FAILED", "tail error", "root")));
        CapabilityExecutionResult.Succeeded result = (CapabilityExecutionResult.Succeeded) mapper.outgoingCallGraph(
                JavaSemanticServiceHttpAdapterTestHelper.targetInvocation(requested), response);

        EvidenceRef evidence = result.evidence().getFirst();
        assertThat(evidence.semanticTarget()).isEqualTo(mapper.semanticTarget(responseRoot));
        assertThat(evidence.content()).contains("warning=NODE_BUDGET_REACHED:")
                .contains("error=CHILD_SEMANTIC_QUERY_FAILED:tail error").hasSizeGreaterThan(1_000);
        assertThat(evidence.artifactRef()).isEqualTo(JavaSemanticArtifactDigest.fromContent(evidence.content()));
        assertThat(result.observations()).extracting(observation -> observation.code())
                .contains(ObservationCode.OPAQUE_EXTERNAL_CALL);
    }

    @Test
    void preservesEveryLegalMethodTargetComponentAndRejectsNonCanonicalKeys() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.MethodTarget expected = new SemanticDtos.MethodTarget(" src/模組: a|b.java", " 包.名 ",
                "服務", "查詢", List.of(" int "));
        SemanticTarget encoded = mapper.semanticTarget(expected);
        SemanticTarget zeroParameters = mapper.semanticTarget(new SemanticDtos.MethodTarget("src/Zero.java", "",
                "Zero", "zero", List.of()));

        assertThat(mapper.methodTarget(encoded)).isEqualTo(expected);
        assertThat(mapper.methodTarget(zeroParameters).parameterTypes()).isEmpty();
        assertThatThrownBy(() -> mapper.methodTarget(new SemanticTarget(SemanticTargetKind.SYMBOL,
                "mt1:05:0:0:0:0:!", java.util.Optional.empty())))
                .isInstanceOf(CapabilityExecutionContractException.class);
        assertThatThrownBy(() -> mapper.methodTarget(new SemanticTarget(SemanticTargetKind.SYMBOL,
                encoded.key() + "x", java.util.Optional.empty())))
                .isInstanceOf(CapabilityExecutionContractException.class);
        assertThatThrownBy(() -> mapper.methodTarget(new SemanticTarget(SemanticTargetKind.SYMBOL,
                "mt1:4:2147483648:", java.util.Optional.empty())))
                .isInstanceOf(CapabilityExecutionContractException.class);
        assertThatThrownBy(() -> mapper.semanticTarget(new SemanticDtos.MethodTarget("src/Trailing.java ", "",
                "Trailing", "trailing", List.of())))
                .isInstanceOf(CapabilityExecutionContractException.class);
        assertThatThrownBy(() -> mapper.semanticTarget(new SemanticDtos.MethodTarget("", "", "Empty", "empty", List.of())))
                .isInstanceOf(CapabilityExecutionContractException.class);
    }

    @Test
    void mapsLongWellFormedFailureDescriptionsWithinRuntimeBound() {
        JavaSemanticErrorMapper mapper = new JavaSemanticErrorMapper(new JavaSemanticResultMapper());
        SemanticDtos.ApiErrorResponse timeout = new SemanticDtos.ApiErrorResponse("SEMANTIC_REQUEST_TIMEOUT",
                "x".repeat(501), "orders", REVISION, null, null, List.of(), "request");

        CapabilityExecutionResult.Failed result = (CapabilityExecutionResult.Failed) mapper.capability(timeout, "operation");

        assertThat(result.failure().code()).isEqualTo(CapabilityExecutionFailureCode.TIMEOUT);
        assertThat(result.failure().description()).hasSize(500);
    }

    private static SemanticDtos.SourceRangePayload sourceRange() {
        SemanticDtos.TextRangePayload range = new SemanticDtos.TextRangePayload(
                new SemanticDtos.Position(0, 0), new SemanticDtos.Position(0, 1));
        return new SemanticDtos.SourceRangePayload("src/ResponseService.java", range);
    }

    private static SemanticDtos.MethodTargetPayload methodTargetPayload() {
        SemanticDtos.JavaTypeIdentityPayload javaType = new SemanticDtos.JavaTypeIdentityPayload("com.example",
                "Orders");
        SemanticDtos.SourceTypeIdentityPayload sourceType = new SemanticDtos.SourceTypeIdentityPayload(javaType,
                "src/Orders.java");
        return new SemanticDtos.MethodTargetPayload(sourceType, "find", List.of());
    }

    private static SemanticDtos.ConceptCandidateResponse conceptCandidate() {
        SemanticDtos.ConceptFollowUpIdentity identity = new SemanticDtos.ConceptFollowUpIdentity("METHOD",
                Optional.empty(), Optional.of(methodTargetPayload()), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        return new SemanticDtos.ConceptCandidateResponse(identity, "Orders.find", List.of("order"), "SYNTAX_RESOLVED",
                Optional.empty(), List.of(), List.of());
    }

    private static SemanticDtos.EvidenceSourceFollowUpIdentity evidenceIdentity() {
        SemanticDtos.MapperStatementKeyPayload key = new SemanticDtos.MapperStatementKeyPayload("orders", "find");
        SemanticDtos.MapperStatementIdentityPayload statement = new SemanticDtos.MapperStatementIdentityPayload(key,
                "src/OrdersMapper.xml", Optional.empty(), 0, "MAPPER_XML_ELEMENT");
        return new SemanticDtos.EvidenceSourceFollowUpIdentity("MAPPER_STATEMENT", Optional.of(statement), Optional.empty());
    }

    private static SemanticDtos.MethodTarget methodTarget(String className, String methodName) {
        return new SemanticDtos.MethodTarget("src/" + className + ".java", "com.example", className, methodName,
                List.of("java.lang.String"));
    }
}
