package com.java.system.agent.codebase.semantic;

import com.java.system.agent.codebase.semantic.dto.SemanticDtos;
import com.java.system.agent.runtime.domain.candidate.RouteCandidate;
import com.java.system.agent.runtime.domain.candidate.SemanticTargetCandidate;
import com.java.system.agent.runtime.domain.evidence.EvidenceRef;
import com.java.system.agent.runtime.domain.observation.ObservationCode;
import com.java.system.agent.runtime.port.out.CapabilityExecutionContractException;
import com.java.system.agent.runtime.port.out.CapabilityExecutionFailureCode;
import com.java.system.agent.runtime.port.out.CapabilityExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Java Semantic Service 成功與 error DTO 的 deterministic runtime mapping 測試
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
                new SemanticDtos.GraphNode("root", target, null, "FULL_SOURCE", "EXPANDED", "SYNCHRONOUS", null,
                        null)), List.of(), List.of(new SemanticDtos.GraphWarning("NODE_BUDGET_REACHED", "limit\nreached",
                "root", null, null, List.of())), List.of());

        CapabilityExecutionResult.Succeeded result = (CapabilityExecutionResult.Succeeded) mapper.outgoingCallGraph(
                JavaSemanticServiceHttpAdapterTestHelper.targetInvocation(), response);

        EvidenceRef evidence = result.evidence().getFirst();
        assertThat(evidence.content()).isEqualTo(
                "root=root; traversal=1/1/3/NODE_BUDGET; node=root:FULL_SOURCE:EXPANDED; warning=NODE_BUDGET_REACHED:limit reached");
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

    private static SemanticDtos.MethodTarget methodTarget(String className, String methodName) {
        return new SemanticDtos.MethodTarget("src/" + className + ".java", "com.example", className, methodName,
                List.of("java.lang.String"));
    }
}
