package com.java.system.agent.codeintelligence.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 驗證 call graph HTTP follow-up 契約會以完整 typed DTO 保留 */
class SemanticDtosContractTest {

    @Test
    void should_deserialize_canonical_follow_up_requests_without_runtime_capability_mapping() throws Exception {
        String responseJson = """
                {
                  "status":"PARTIAL",
                  "analyzedRevision":"0123456789012345678901234567890123456789",
                  "rootNodeId":"root",
                  "traversal":{"requestedDepth":1,"expandedNodeCount":1,"nodeBudget":20,"rootDirectCallsComplete":true,"limitReason":"NONE"},
                  "nodes":[{
                    "nodeId":"root",
                    "target":{"sourceType":{"javaType":{"packageName":"com.acme","className":"OrderService"},"sourceFile":"src/main/java/com/acme/OrderService.java"},"methodName":"find","parameterTypes":[]},
                    "externalSymbol":null,
                    "contentState":"FULL_SOURCE",
                    "traversalState":"EXPANDED",
                    "dispatchKind":"SYNCHRONOUS",
                    "declarationRange":null,
                    "availableFollowUps":[{
                      "operation":"GET_METHOD_SOURCE",
                      "api":{"method":"POST","path":"/v1/discovery/method-source","operationId":"getMethodSource"},
                      "request":{"repoId":"orders","expectedRevision":"0123456789012345678901234567890123456789","target":{"sourceType":{"javaType":{"packageName":"com.acme","className":"OrderService"},"sourceFile":"src/main/java/com/acme/OrderService.java"},"methodName":"find","parameterTypes":[]}}
                    }]
                  }],
                  "edges":[],
                  "warnings":[{
                    "code":"SOURCE_TRUNCATED",
                    "message":"continue with exact range",
                    "nodeId":"root",
                    "callExpression":null,
                    "callSite":null,
                    "candidates":[],
                    "availableFollowUps":[{
                      "operation":"GET_SOURCE_SEGMENT",
                      "api":{"method":"POST","path":"/v1/discovery/source-segment","operationId":"getSourceSegment"},
                      "request":{"repoId":"orders","expectedRevision":"0123456789012345678901234567890123456789","location":{"sourceFile":"src/main/java/com/acme/OrderService.java","range":{"start":{"line":3,"character":4},"end":{"line":3,"character":12}}},"contextLines":0}
                    }]
                  }],
                  "errors":[]
                }
                """;

        SemanticDtos.OutgoingCallGraphResponse response = new ObjectMapper().readValue(
                responseJson, SemanticDtos.OutgoingCallGraphResponse.class);

        SemanticDtos.AvailableFollowUp methodFollowUp = response.nodes().getFirst().availableFollowUps().getFirst();
        SemanticDtos.AvailableFollowUp segmentFollowUp = response.warnings().getFirst().availableFollowUps().getFirst();
        assertThat(methodFollowUp.operation()).isEqualTo("GET_METHOD_SOURCE");
        assertThat(response.nodes().getFirst().target().sourceFile())
                .isEqualTo("src/main/java/com/acme/OrderService.java");
        assertThat(methodFollowUp.api().method()).isEqualTo("POST");
        assertThat(methodFollowUp.api().path()).isEqualTo("/v1/discovery/method-source");
        assertThat(methodFollowUp.request()).isInstanceOf(SemanticDtos.TargetFollowUpRequest.class);
        SemanticDtos.TargetFollowUpRequest methodRequest =
                (SemanticDtos.TargetFollowUpRequest) methodFollowUp.request();
        assertThat(methodRequest.target()).isInstanceOf(SemanticDtos.MethodTargetPayload.class);
        SemanticDtos.MethodTargetPayload methodTarget = (SemanticDtos.MethodTargetPayload) methodRequest.target();
        assertThat(methodTarget.sourceType().javaType().packageName()).isEqualTo("com.acme");
        assertThat(methodTarget.sourceType().javaType().className()).isEqualTo("OrderService");
        assertThat(methodTarget.sourceType().sourceFile())
                .isEqualTo("src/main/java/com/acme/OrderService.java");
        assertThat(methodTarget.methodName()).isEqualTo("find");
        assertThat(methodTarget.parameterTypes()).isEmpty();
        assertThat(segmentFollowUp.operation()).isEqualTo("GET_SOURCE_SEGMENT");
        assertThat(segmentFollowUp.api().method()).isEqualTo("POST");
        assertThat(segmentFollowUp.api().path()).isEqualTo("/v1/discovery/source-segment");
        assertThat(segmentFollowUp.request()).isInstanceOf(SemanticDtos.SourceSegmentFollowUpRequest.class);
        SemanticDtos.SourceSegmentFollowUpRequest segmentRequest =
                (SemanticDtos.SourceSegmentFollowUpRequest) segmentFollowUp.request();
        assertThat(segmentRequest.location().sourceFile()).isEqualTo("src/main/java/com/acme/OrderService.java");
        assertThat(segmentRequest.location().range().start().line()).isEqualTo(3);
        assertThat(segmentRequest.location().range().start().character()).isEqualTo(4);
        assertThat(segmentRequest.location().range().end().line()).isEqualTo(3);
        assertThat(segmentRequest.location().range().end().character()).isEqualTo(12);
        assertThat(segmentRequest.contextLines()).isZero();
    }
}
