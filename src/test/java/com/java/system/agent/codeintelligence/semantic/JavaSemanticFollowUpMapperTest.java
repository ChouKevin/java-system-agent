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

    @Test
    void maps_all_ten_concept_identities_and_three_evidence_identities() {
        SemanticDtos.MethodTargetPayload target = methodTarget();
        SemanticDtos.SourceTypeIdentityPayload sourceType = target.sourceType();
        SemanticDtos.SourceMemberIdentityPayload member = new SemanticDtos.SourceMemberIdentityPayload.TypeMember(
                sourceType, "field");
        SemanticDtos.MapperStatementKeyPayload key = new SemanticDtos.MapperStatementKeyPayload("mapper", "find");
        SemanticDtos.MapperStatementIdentityPayload statement = new SemanticDtos.MapperStatementIdentityPayload(
                key, "src/main/resources/Mapper.xml", Optional.empty(), 0, "MAPPER_XML_ELEMENT");
        SemanticDtos.DeclarationSubjectPayload declaration =
                new SemanticDtos.TypeDeclarationSubjectPayload("TYPE", sourceType);
        SemanticDtos.JavaTypeIdentityPayload javaType = sourceType.javaType();
        List<SemanticDtos.ConceptFollowUpIdentity> concepts = List.of(
                concept("TYPE", sourceType, target, member, key, statement, declaration, javaType),
                concept("METHOD", sourceType, target, member, key, statement, declaration, javaType),
                concept("FIELD", sourceType, target, member, key, statement, declaration, javaType),
                concept("ANNOTATION_USAGE", sourceType, target, member, key, statement, declaration, javaType),
                concept("TYPE_USAGE", sourceType, target, member, key, statement, declaration, javaType),
                concept("API_ROUTE", sourceType, target, member, key, statement, declaration, javaType),
                concept("MQ_DESTINATION", sourceType, target, member, key, statement, declaration, javaType),
                concept("SCHEDULE", sourceType, target, member, key, statement, declaration, javaType),
                concept("MAPPER_STATEMENT", sourceType, target, member, key, statement, declaration, javaType),
                concept("MAPPER_STATEMENT_VARIANT", sourceType, target, member, key, statement, declaration, javaType));
        JavaSemanticFollowUpMapper mapper = new JavaSemanticFollowUpMapper();

        for (SemanticDtos.ConceptFollowUpIdentity concept : concepts) {
            SemanticDtos.AvailableFollowUp followUp = new SemanticDtos.AvailableFollowUp("RESOLVE_CONCEPT",
                    new SemanticDtos.FollowUpApi("POST", "/v1/discovery/concepts/resolve", "resolveConcept"),
                    new SemanticDtos.IdentityFollowUpRequest("orders", "FIXTURE", concept));
            assertThat(mapper.map(new RepositoryId("orders"), new RepositoryRevision("FIXTURE"), followUp)
                    .targetCapabilityName()).isEqualTo("codebase_resolve_concept");
        }

        List<SemanticDtos.EvidenceSourceFollowUpIdentity> evidence = List.of(
                new SemanticDtos.EvidenceSourceFollowUpIdentity("ANNOTATION_SQL", Optional.of(statement), Optional.empty()),
                new SemanticDtos.EvidenceSourceFollowUpIdentity("MAPPER_STATEMENT", Optional.of(statement), Optional.empty()),
                new SemanticDtos.EvidenceSourceFollowUpIdentity("MAPPER_FRAGMENT", Optional.empty(), Optional.of(
                        new SemanticDtos.MapperFragmentIdentityPayload("mapper", "fragment", "src/main/resources/Mapper.xml",
                                0, "MAPPER_XML_ELEMENT"))));
        for (SemanticDtos.EvidenceSourceFollowUpIdentity identity : evidence) {
            SemanticDtos.AvailableFollowUp followUp = new SemanticDtos.AvailableFollowUp("GET_EVIDENCE_SOURCE",
                    new SemanticDtos.FollowUpApi("POST", "/v1/discovery/evidence-source", "getEvidenceSource"),
                    new SemanticDtos.IdentityFollowUpRequest("orders", "FIXTURE", identity));
            assertThat(mapper.map(new RepositoryId("orders"), new RepositoryRevision("FIXTURE"), followUp)
                    .targetCapabilityName()).isEqualTo("codebase_get_evidence_source");
        }
    }

    @Test
    void rejects_cross_operation_identities_and_invalid_follow_up_bounds() {
        SemanticDtos.MethodTargetPayload target = methodTarget();
        SemanticDtos.MapperStatementIdentityPayload statement = new SemanticDtos.MapperStatementIdentityPayload(
                new SemanticDtos.MapperStatementKeyPayload("mapper", "find"), "src/main/resources/Mapper.xml",
                Optional.empty(), 0, "MAPPER_XML_ELEMENT");
        SemanticDtos.EvidenceSourceFollowUpIdentity evidence = new SemanticDtos.EvidenceSourceFollowUpIdentity(
                "MAPPER_STATEMENT", Optional.of(statement), Optional.empty());
        JavaSemanticFollowUpMapper mapper = new JavaSemanticFollowUpMapper();
        SemanticDtos.AvailableFollowUp wrongIdentity = new SemanticDtos.AvailableFollowUp("RESOLVE_CONCEPT",
                new SemanticDtos.FollowUpApi("POST", "/v1/discovery/concepts/resolve", "resolveConcept"),
                new SemanticDtos.IdentityFollowUpRequest("orders", "FIXTURE", evidence));
        SemanticDtos.AvailableFollowUp invalidDepth = new SemanticDtos.AvailableFollowUp("ANALYZE_OUTGOING_CALL_GRAPH",
                new SemanticDtos.FollowUpApi("POST", "/v1/analyses/call-graphs/outgoing", "analyzeOutgoingCallGraph"),
                new SemanticDtos.TargetFollowUpRequest("orders", "FIXTURE", target,
                        Optional.of(3), Optional.empty(), Optional.empty()));
        SemanticDtos.AvailableFollowUp mixedConcept = new SemanticDtos.AvailableFollowUp("RESOLVE_CONCEPT",
                new SemanticDtos.FollowUpApi("POST", "/v1/discovery/concepts/resolve", "resolveConcept"),
                new SemanticDtos.IdentityFollowUpRequest("orders", "FIXTURE",
                        new SemanticDtos.ConceptFollowUpIdentity("TYPE", Optional.of(target.sourceType()), Optional.of(target),
                                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                                Optional.empty(), Optional.empty(), Optional.empty())));

        assertThatThrownBy(() -> mapper.map(new RepositoryId("orders"), new RepositoryRevision("FIXTURE"), wrongIdentity))
                .isInstanceOf(CapabilityExecutionContractException.class);
        assertThatThrownBy(() -> mapper.map(new RepositoryId("orders"), new RepositoryRevision("FIXTURE"), invalidDepth))
                .isInstanceOf(CapabilityExecutionContractException.class);
        assertThatThrownBy(() -> mapper.map(new RepositoryId("orders"), new RepositoryRevision("FIXTURE"), mixedConcept))
                .isInstanceOf(CapabilityExecutionContractException.class);
    }

    @Test
    void maps_the_remaining_operation_specific_request_shapes() {
        SemanticDtos.MethodTargetPayload target = methodTarget();
        SemanticDtos.SourceTypeIdentityPayload sourceType = target.sourceType();
        SemanticDtos.InternalReferenceFollowUpTarget reference = new SemanticDtos.InternalReferenceFollowUpTarget(
                "METHOD", target);
        JavaSemanticFollowUpMapper mapper = new JavaSemanticFollowUpMapper();
        List<SemanticDtos.AvailableFollowUp> followUps = List.of(
                new SemanticDtos.AvailableFollowUp("DISCOVER_METHOD_IMPLEMENTATIONS",
                        new SemanticDtos.FollowUpApi("POST", "/v1/discovery/method-implementations", "discoverMethodImplementations"),
                        new SemanticDtos.DiscoverMethodImplementationsFollowUpRequest("orders", "FIXTURE", target)),
                new SemanticDtos.AvailableFollowUp("DISCOVER_CONCEPTS",
                        new SemanticDtos.FollowUpApi("POST", "/v1/discovery/concepts", "discoverConcepts"),
                        new SemanticDtos.DiscoverConceptsFollowUpRequest("orders", "FIXTURE",
                                List.of(new SemanticDtos.ConceptSearchTermPayload("order", "EXACT")), List.of("TYPE"),
                                "ALL", Optional.empty(), 0, 1)),
                new SemanticDtos.AvailableFollowUp("DISCOVER_EVENT_LISTENERS",
                        new SemanticDtos.FollowUpApi("POST", "/v1/discovery/event-listeners", "discoverEventListeners"),
                        new SemanticDtos.DiscoverEventListenersFollowUpRequest("orders", "FIXTURE", "OrderCreated", 0, 1)),
                new SemanticDtos.AvailableFollowUp("RESOLVE_SOURCE_SYMBOL",
                        new SemanticDtos.FollowUpApi("POST", "/v1/discovery/source-symbols/resolve", "resolveSourceSymbol"),
                        new SemanticDtos.ResolveSourceSymbolFollowUpRequest("orders", "FIXTURE",
                                new SemanticDtos.SourceSymbolContextPayload(sourceType.javaType(), Optional.of("Orders.java"),
                                        Optional.empty()), "find", Optional.empty())),
                new SemanticDtos.AvailableFollowUp("FIND_INTERNAL_REFERENCES",
                        new SemanticDtos.FollowUpApi("POST", "/v1/discovery/internal-references", "findInternalReferences"),
                        new SemanticDtos.TargetFollowUpRequest("orders", "FIXTURE", reference,
                                Optional.empty(), Optional.of(0), Optional.of(1))),
                new SemanticDtos.AvailableFollowUp("GET_SOURCE_SEGMENT",
                        new SemanticDtos.FollowUpApi("POST", "/v1/discovery/source-segment", "getSourceSegment"),
                        new SemanticDtos.SourceSegmentFollowUpRequest("orders", "FIXTURE",
                                new SemanticDtos.SourceRangePayload("Orders.java", new SemanticDtos.TextRangePayload(
                                        new SemanticDtos.Position(0, 0), new SemanticDtos.Position(0, 1))), 0)));
        List<String> capabilities = List.of("codebase_discover_method_implementations", "codebase_discover_concepts",
                "codebase_discover_event_listeners", "codebase_resolve_source_symbol",
                "codebase_find_internal_references", "codebase_get_source_segment");

        for (int index = 0; index < followUps.size(); index++) {
            assertThat(mapper.map(new RepositoryId("orders"), new RepositoryRevision("FIXTURE"), followUps.get(index))
                    .targetCapabilityName()).isEqualTo(capabilities.get(index));
        }
    }

    private static SemanticDtos.MethodTargetPayload methodTarget() {
        return new SemanticDtos.MethodTargetPayload(new SemanticDtos.SourceTypeIdentityPayload(
                new SemanticDtos.JavaTypeIdentityPayload("com.acme", "Orders"), "Orders.java"), "find", List.of());
    }

    private static SemanticDtos.ConceptFollowUpIdentity concept(String kind,
            SemanticDtos.SourceTypeIdentityPayload sourceType, SemanticDtos.MethodTargetPayload target,
            SemanticDtos.SourceMemberIdentityPayload member, SemanticDtos.MapperStatementKeyPayload key,
            SemanticDtos.MapperStatementIdentityPayload statement, SemanticDtos.DeclarationSubjectPayload declaration,
            SemanticDtos.JavaTypeIdentityPayload javaType) {
        return switch (kind) {
            case "TYPE" -> new SemanticDtos.ConceptFollowUpIdentity(kind, Optional.of(sourceType), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty());
            case "METHOD" -> new SemanticDtos.ConceptFollowUpIdentity(kind, Optional.empty(), Optional.of(target),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty());
            case "FIELD" -> new SemanticDtos.ConceptFollowUpIdentity(kind, Optional.empty(), Optional.empty(),
                    Optional.of((SemanticDtos.ConceptIdentityTargetPayload) member), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
            case "ANNOTATION_USAGE" -> new SemanticDtos.ConceptFollowUpIdentity(kind, Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.of(declaration), Optional.of(new SemanticDtos.ResolvedAnnotationTypePayload(
                    "RESOLVED", javaType)), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
            case "TYPE_USAGE" -> new SemanticDtos.ConceptFollowUpIdentity(kind, Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(declaration),
                    Optional.of(new SemanticDtos.TypeUsageLocationPayload("RETURN", 0)), Optional.of(List.of(
                    new SemanticDtos.TypeArgumentPathPayload("TYPE_ARGUMENT", 0))), Optional.of(
                    new SemanticDtos.ReferencedTypePayload(javaType, 0)), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
            case "API_ROUTE" -> new SemanticDtos.ConceptFollowUpIdentity(kind, Optional.empty(), Optional.of(target),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.of("GET"), Optional.of("/orders"), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty());
            case "MQ_DESTINATION" -> new SemanticDtos.ConceptFollowUpIdentity(kind, Optional.empty(), Optional.of(target),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("KAFKA"),
                    Optional.of("orders"), Optional.empty(), Optional.empty());
            case "SCHEDULE" -> new SemanticDtos.ConceptFollowUpIdentity(kind, Optional.empty(), Optional.of(target),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.of("CRON"), Optional.of("0 * * * * *"));
            case "MAPPER_STATEMENT" -> new SemanticDtos.ConceptFollowUpIdentity(kind, Optional.empty(), Optional.empty(),
                    Optional.of(key), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty());
            case "MAPPER_STATEMENT_VARIANT" -> new SemanticDtos.ConceptFollowUpIdentity(kind, Optional.empty(), Optional.empty(),
                    Optional.of(statement), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty());
            default -> throw new IllegalArgumentException("unsupported concept kind");
        };
    }
}
