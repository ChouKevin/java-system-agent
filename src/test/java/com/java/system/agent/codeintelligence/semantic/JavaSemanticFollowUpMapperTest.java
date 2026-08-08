package com.java.system.agent.codeintelligence.semantic;

import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.port.out.CapabilityExecutionContractException;
import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 驗證 provider follow-up 只會映射至既定 capability payload */
class JavaSemanticFollowUpMapperTest {

    @Test
    void maps_method_source_follow_up_to_bound_capability() {
        SemanticDtos.MethodTargetPayload target = new SemanticDtos.MethodTargetPayload(
                new SemanticDtos.SourceTypeIdentityPayload(
                        new SemanticDtos.JavaTypeIdentityPayload("com.acme", "Orders"), "Orders.java"),
                "find", List.of());
        SemanticDtos.AvailableFollowUp followUp = new SemanticDtos.AvailableFollowUp("GET_METHOD_SOURCE",
                new SemanticDtos.FollowUpApi("POST", "/v1/discovery/method-source", "getMethodSource"),
                new SemanticDtos.TargetFollowUpRequest("orders", "FIXTURE", target,
                        java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty()));

        assertThat(new JavaSemanticFollowUpMapper().map(new RepositoryId("orders"),
                new RepositoryRevision("FIXTURE"), followUp).targetCapabilityName())
                .isEqualTo("codebase_get_method_source");
    }

    @Test
    void maps_both_type_member_operations_with_their_shared_request_shape() {
        SemanticDtos.SourceTypeIdentityPayload sourceType = new SemanticDtos.SourceTypeIdentityPayload(
                new SemanticDtos.JavaTypeIdentityPayload("com.acme", "Orders"), "Orders.java");
        SemanticDtos.TypeMembersFollowUpRequest request = new SemanticDtos.TypeMembersFollowUpRequest(
                "orders", "FIXTURE", sourceType, List.of("METHOD"), java.util.Optional.empty(), 0, 50);
        JavaSemanticFollowUpMapper mapper = new JavaSemanticFollowUpMapper();

        for (String operation : List.of("GET_TYPE_MEMBERS", "DISCOVER_TYPE_MEMBERS")) {
            SemanticDtos.AvailableFollowUp followUp = new SemanticDtos.AvailableFollowUp(operation,
                    new SemanticDtos.FollowUpApi("POST", "/v1/discovery/type-members", "discoverTypeMembers"), request);
            assertThat(mapper.map(new RepositoryId("orders"), new RepositoryRevision("FIXTURE"), followUp)
                    .targetCapabilityName()).isEqualTo("codebase_discover_type_members");
        }
    }

    @Test
    void maps_shared_target_operations_and_rejects_mixed_target_fields() {
        SemanticDtos.MethodTargetPayload target = new SemanticDtos.MethodTargetPayload(
                new SemanticDtos.SourceTypeIdentityPayload(
                        new SemanticDtos.JavaTypeIdentityPayload("com.acme", "Orders"), "Orders.java"),
                "find", List.of());
        JavaSemanticFollowUpMapper mapper = new JavaSemanticFollowUpMapper();

        for (String operation : List.of("ANALYZE_OUTGOING_CALL_GRAPH", "ANALYZE_INCOMING_CALL_GRAPH")) {
            SemanticDtos.AvailableFollowUp followUp = new SemanticDtos.AvailableFollowUp(operation,
                    new SemanticDtos.FollowUpApi("POST", operation.contains("OUTGOING")
                            ? "/v1/analyses/call-graphs/outgoing" : "/v1/analyses/call-graphs/incoming",
                            operation.contains("OUTGOING") ? "analyzeOutgoingCallGraph" : "analyzeIncomingCallGraph"),
                    new SemanticDtos.TargetFollowUpRequest("orders", "FIXTURE", target,
                            Optional.of(1), Optional.empty(), Optional.empty()));
            assertThat(mapper.map(new RepositoryId("orders"), new RepositoryRevision("FIXTURE"), followUp)
                    .targetCapabilityName()).isEqualTo(operation.contains("OUTGOING")
                            ? "codebase_outgoing_call_graph" : "codebase_incoming_call_graph");
        }

        SemanticDtos.AvailableFollowUp mixedMethodSource = new SemanticDtos.AvailableFollowUp("GET_METHOD_SOURCE",
                new SemanticDtos.FollowUpApi("POST", "/v1/discovery/method-source", "getMethodSource"),
                new SemanticDtos.TargetFollowUpRequest("orders", "FIXTURE", target,
                        Optional.of(1), Optional.empty(), Optional.empty()));
        assertThatThrownBy(() -> mapper.map(new RepositoryId("orders"), new RepositoryRevision("FIXTURE"), mixedMethodSource))
                .isInstanceOf(CapabilityExecutionContractException.class);

        assertThatThrownBy(() -> new SemanticDtos.InternalReferenceFollowUpTarget("TYPE", target))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
