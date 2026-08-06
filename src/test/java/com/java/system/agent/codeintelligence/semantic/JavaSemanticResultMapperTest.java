package com.java.system.agent.codeintelligence.semantic;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Java Semantic Service 成功與 error DTO 的 deterministic answering mapping 測試
 */
class JavaSemanticResultMapperTest {

    private static final String REVISION = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

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

    private static SemanticDtos.MethodTarget methodTarget(String className, String methodName) {
        return new SemanticDtos.MethodTarget("src/" + className + ".java", "com.example", className, methodName,
                List.of("java.lang.String"));
    }
}
