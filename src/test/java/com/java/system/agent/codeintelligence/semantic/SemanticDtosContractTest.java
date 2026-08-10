package com.java.system.agent.codeintelligence.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void deserializes_closed_concept_and_evidence_identities_and_rejects_unknown_members() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String conceptJson = """
                {"kind":"API_ROUTE","target":{"sourceType":{"javaType":{"packageName":"com.acme","className":"Orders"},"sourceFile":"Orders.java"},"methodName":"find","parameterTypes":[]},"httpVerb":"GET","route":"/orders"}
                """;
        String evidenceJson = """
                {"kind":"MAPPER_FRAGMENT","fragmentIdentity":{"namespace":"mapper","fragmentId":"find","resourcePath":"src/main/resources/Mapper.xml","documentOrdinal":0,"representation":"MAPPER_XML_ELEMENT"}}
                """;

        assertThat(mapper.readValue(conceptJson, SemanticDtos.ConceptFollowUpIdentity.class).kind())
                .isEqualTo("API_ROUTE");
        assertThat(mapper.readValue(evidenceJson, SemanticDtos.EvidenceSourceFollowUpIdentity.class).fragmentIdentity())
                .isPresent();
        SemanticDtos.AvailableFollowUp resolveFollowUp = mapper.readValue("""
                {"operation":"RESOLVE_CONCEPT","api":{"method":"POST","path":"/v1/discovery/concepts/resolve","operationId":"resolveConcept"},"request":{"repoId":"orders","expectedRevision":"FIXTURE","identity":%s}}
                """.formatted(conceptJson), SemanticDtos.AvailableFollowUp.class);
        SemanticDtos.AvailableFollowUp evidenceFollowUp = mapper.readValue("""
                {"operation":"GET_EVIDENCE_SOURCE","api":{"method":"POST","path":"/v1/discovery/evidence-source","operationId":"getEvidenceSource"},"request":{"repoId":"orders","expectedRevision":"FIXTURE","identity":%s}}
                """.formatted(evidenceJson), SemanticDtos.AvailableFollowUp.class);
        assertThat(((SemanticDtos.IdentityFollowUpRequest) resolveFollowUp.request()).identity())
                .isInstanceOf(SemanticDtos.ConceptFollowUpIdentity.class);
        assertThat(((SemanticDtos.IdentityFollowUpRequest) evidenceFollowUp.request()).identity())
                .isInstanceOf(SemanticDtos.EvidenceSourceFollowUpIdentity.class);
        assertThatThrownBy(() -> mapper.readValue("""
                {"kind":"TYPE","sourceType":{"javaType":{"packageName":"com.acme","className":"Orders"},"sourceFile":"Orders.java"},"unknown":true}
                """, SemanticDtos.ConceptFollowUpIdentity.class)).isInstanceOf(Exception.class);
    }

    @Test
    void preserves_provider_source_member_scope_discriminator() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SemanticDtos.SourceMemberIdentityPayload identity = mapper.readValue("""
                {"scope":"TYPE","ownerType":{"javaType":{"packageName":"com.acme","className":"Orders"},"sourceFile":"Orders.java"},"name":"status"}
                """, SemanticDtos.SourceMemberIdentityPayload.class);

        assertThat(identity).isInstanceOf(SemanticDtos.SourceMemberIdentityPayload.TypeMember.class);
        assertThat(mapper.valueToTree(identity).path("scope").asText()).isEqualTo("TYPE");
    }

    @Test
    void rejects_malformed_implementation_target_identity_and_extra_follow_up_members() {
        ObjectMapper mapper = new ObjectMapper();

        assertThatThrownBy(() -> mapper.readValue("""
                {"operation":"DISCOVER_METHOD_IMPLEMENTATIONS","api":{"method":"POST","path":"/v1/discovery/method-implementations","operationId":"discoverMethodImplementations"},"request":{"repoId":"orders","expectedRevision":"FIXTURE","declarationTarget":{"sourceType":{"javaType":{"packageName":"com.acme","className":"OrderLookup"}},"methodName":"findById","parameterTypes":[]}}}
                """, SemanticDtos.AvailableFollowUp.class)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> mapper.readValue("""
                {"operation":"DISCOVER_METHOD_IMPLEMENTATIONS","api":{"method":"POST","path":"/v1/discovery/method-implementations","operationId":"discoverMethodImplementations"},"request":{"repoId":"orders","expectedRevision":"FIXTURE","declarationTarget":{"sourceType":{"javaType":{"packageName":"com.acme","className":"OrderLookup"},"sourceFile":"OrderLookup.java"},"methodName":"findById","parameterTypes":[]}},"unexpected":true}
                """, SemanticDtos.AvailableFollowUp.class)).isInstanceOf(Exception.class);
    }
}
