package com.java.system.agent.codeintelligence.semantic;

import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.port.out.CapabilityExecutionContractException;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.codeintelligence.planning.GetEvidenceSourceExecutionInput;
import com.java.system.agent.codeintelligence.planning.ResolveConceptExecutionInput;
import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import jakarta.validation.Validation;
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

        com.java.system.agent.answering.domain.candidate.FollowUpCandidate candidate =
                new JavaSemanticFollowUpMapper().map(new RepositoryId("orders"),
                        new RepositoryRevision("FIXTURE"), followUp);

        assertThat(candidate.targetCapabilityName()).isEqualTo("codebase_get_method_source");
        assertThat(candidate.description())
                .isEqualTo("Semantic follow-up GET_METHOD_SOURCE target=com.acme.Orders#find()");
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
            com.java.system.agent.answering.domain.candidate.FollowUpCandidate candidate = mapper.map(
                    new RepositoryId("orders"), new RepositoryRevision("FIXTURE"), followUp);
            assertThat(candidate.targetCapabilityName()).isEqualTo("codebase_discover_type_members");
            assertThat(candidate.description())
                    .isEqualTo("Semantic follow-up DISCOVER_TYPE_MEMBERS target=com.acme.Orders");
        }
    }

    @Test
    void describes_implementation_and_internal_reference_follow_up_targets_without_exposing_payloads() {
        SemanticDtos.MethodTargetPayload method = methodTarget();
        SemanticDtos.SourceMemberIdentityPayload field = new SemanticDtos.SourceMemberIdentityPayload.TypeMember(
                "TYPE", method.sourceType(), "repository");
        SemanticDtos.AvailableFollowUp implementation = new SemanticDtos.AvailableFollowUp(
                "DISCOVER_METHOD_IMPLEMENTATIONS",
                new SemanticDtos.FollowUpApi("POST", "/v1/discovery/method-implementations",
                        "discoverMethodImplementations"),
                new SemanticDtos.DiscoverMethodImplementationsFollowUpRequest("orders", "FIXTURE", method));
        SemanticDtos.AvailableFollowUp references = new SemanticDtos.AvailableFollowUp(
                "FIND_INTERNAL_REFERENCES",
                new SemanticDtos.FollowUpApi("POST", "/v1/discovery/internal-references",
                        "findInternalReferences"),
                new SemanticDtos.TargetFollowUpRequest("orders", "FIXTURE",
                        new SemanticDtos.InternalReferenceFollowUpTarget("MEMBER", field),
                        Optional.empty(), Optional.of(0), Optional.of(50)));
        JavaSemanticFollowUpMapper mapper = new JavaSemanticFollowUpMapper();

        assertThat(mapper.map(new RepositoryId("orders"), new RepositoryRevision("FIXTURE"), implementation)
                .description()).isEqualTo(
                        "Semantic follow-up DISCOVER_METHOD_IMPLEMENTATIONS target=com.acme.Orders#find()");
        assertThat(mapper.map(new RepositoryId("orders"), new RepositoryRevision("FIXTURE"), references)
                .description()).isEqualTo(
                        "Semantic follow-up FIND_INTERNAL_REFERENCES target=MEMBER com.acme.Orders#repository");
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
                "TYPE", sourceType, "field");
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
    void rejects_provider_follow_up_enum_values_and_duplicate_concept_kinds() {
        SemanticDtos.SourceTypeIdentityPayload sourceType = methodTarget().sourceType();
        JavaSemanticFollowUpMapper mapper = new JavaSemanticFollowUpMapper();
        SemanticDtos.AvailableFollowUp invalidMemberKind = new SemanticDtos.AvailableFollowUp("GET_TYPE_MEMBERS",
                new SemanticDtos.FollowUpApi("POST", "/v1/discovery/type-members", "discoverTypeMembers"),
                new SemanticDtos.TypeMembersFollowUpRequest("orders", "FIXTURE", sourceType,
                        List.of("UNKNOWN"), Optional.empty(), 0, 1));
        SemanticDtos.AvailableFollowUp duplicateConceptKinds = new SemanticDtos.AvailableFollowUp("DISCOVER_CONCEPTS",
                new SemanticDtos.FollowUpApi("POST", "/v1/discovery/concepts", "discoverConcepts"),
                new SemanticDtos.DiscoverConceptsFollowUpRequest("orders", "FIXTURE",
                        List.of(new SemanticDtos.ConceptSearchTermPayload("orders", "TOKEN_EXACT")),
                        List.of("TYPE", "TYPE"), "ALL", Optional.empty(), 0, 1));

        assertThatThrownBy(() -> mapper.map(new RepositoryId("orders"), new RepositoryRevision("FIXTURE"), invalidMemberKind))
                .isInstanceOf(CapabilityExecutionContractException.class);
        assertThatThrownBy(() -> mapper.map(new RepositoryId("orders"), new RepositoryRevision("FIXTURE"), duplicateConceptKinds))
                .isInstanceOf(CapabilityExecutionContractException.class);
    }

    @Test
    void rejects_unknown_operations_and_repository_revision_scope_mismatches() {
        SemanticDtos.MethodTargetPayload target = methodTarget();
        SemanticDtos.FollowUpApi implementationApi = new SemanticDtos.FollowUpApi("POST",
                "/v1/discovery/method-implementations", "discoverMethodImplementations");
        JavaSemanticFollowUpMapper mapper = new JavaSemanticFollowUpMapper();
        SemanticDtos.AvailableFollowUp unknownOperation = new SemanticDtos.AvailableFollowUp("UNKNOWN_OPERATION",
                implementationApi, new SemanticDtos.DiscoverMethodImplementationsFollowUpRequest("orders", "FIXTURE", target));
        SemanticDtos.AvailableFollowUp mismatchedScope = new SemanticDtos.AvailableFollowUp(
                "DISCOVER_METHOD_IMPLEMENTATIONS", implementationApi,
                new SemanticDtos.DiscoverMethodImplementationsFollowUpRequest("other-orders", "OTHER", target));

        assertThatThrownBy(() -> mapper.map(new RepositoryId("orders"), new RepositoryRevision("FIXTURE"), unknownOperation))
                .isInstanceOf(CapabilityExecutionContractException.class);
        assertThatThrownBy(() -> mapper.map(new RepositoryId("orders"), new RepositoryRevision("FIXTURE"), mismatchedScope))
                .isInstanceOf(CapabilityExecutionContractException.class);
    }

    @Test
    void rejects_source_segment_follow_up_with_reversed_range() {
        assertThatThrownBy(() -> new SemanticDtos.SourceRangePayload("Orders.java", new SemanticDtos.TextRangePayload(
                new SemanticDtos.Position(10, 0), new SemanticDtos.Position(9, 0))))
                .isInstanceOf(IllegalArgumentException.class);
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
                                List.of(new SemanticDtos.ConceptSearchTermPayload("order", "TOKEN_EXACT")), List.of("TYPE"),
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

    @Test
    void decodes_identity_follow_up_canonical_payloads_without_losing_source_member_scope() {
        SemanticDtos.MethodTargetPayload target = methodTarget();
        SemanticDtos.SourceMemberIdentityPayload member = new SemanticDtos.SourceMemberIdentityPayload.TypeMember(
                "TYPE", target.sourceType(), "status");
        SemanticDtos.ConceptFollowUpIdentity concept = concept("FIELD", target.sourceType(), target, member,
                new SemanticDtos.MapperStatementKeyPayload("mapper", "find"), mapperStatementIdentity(),
                new SemanticDtos.TypeDeclarationSubjectPayload("TYPE", target.sourceType()), target.sourceType().javaType());
        SemanticDtos.EvidenceSourceFollowUpIdentity evidence = new SemanticDtos.EvidenceSourceFollowUpIdentity(
                "MAPPER_STATEMENT", Optional.of(mapperStatementIdentity()), Optional.empty());
        JavaSemanticFollowUpMapper mapper = new JavaSemanticFollowUpMapper();
        CanonicalCapabilityPayloadCodec codec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        com.java.system.agent.answering.domain.candidate.FollowUpCandidate conceptCandidate = mapper.map(
                new RepositoryId("orders"), new RepositoryRevision("FIXTURE"), new SemanticDtos.AvailableFollowUp(
                        "RESOLVE_CONCEPT", new SemanticDtos.FollowUpApi("POST", "/v1/discovery/concepts/resolve", "resolveConcept"),
                        new SemanticDtos.IdentityFollowUpRequest("orders", "FIXTURE", concept)));
        com.java.system.agent.answering.domain.candidate.FollowUpCandidate evidenceCandidate = mapper.map(
                new RepositoryId("orders"), new RepositoryRevision("FIXTURE"), new SemanticDtos.AvailableFollowUp(
                        "GET_EVIDENCE_SOURCE", new SemanticDtos.FollowUpApi("POST", "/v1/discovery/evidence-source", "getEvidenceSource"),
                        new SemanticDtos.IdentityFollowUpRequest("orders", "FIXTURE", evidence)));

        ResolveConceptExecutionInput decodedConcept = codec.decode(conceptCandidate.payload(), ResolveConceptExecutionInput.class);
        GetEvidenceSourceExecutionInput decodedEvidence = codec.decode(evidenceCandidate.payload(), GetEvidenceSourceExecutionInput.class);
        SemanticDtos.SourceMemberIdentityPayload.TypeMember decodedMember =
                (SemanticDtos.SourceMemberIdentityPayload.TypeMember) decodedConcept.identity().identity().orElseThrow();
        assertThat(decodedMember.scope()).isEqualTo("TYPE");
        assertThat(decodedEvidence.identity()).isEqualTo(evidence);
    }

    private static SemanticDtos.MethodTargetPayload methodTarget() {
        return new SemanticDtos.MethodTargetPayload(new SemanticDtos.SourceTypeIdentityPayload(
                new SemanticDtos.JavaTypeIdentityPayload("com.acme", "Orders"), "Orders.java"), "find", List.of());
    }

    private static SemanticDtos.MapperStatementIdentityPayload mapperStatementIdentity() {
        return new SemanticDtos.MapperStatementIdentityPayload(new SemanticDtos.MapperStatementKeyPayload("mapper", "find"),
                "src/main/resources/Mapper.xml", Optional.empty(), 0, "MAPPER_XML_ELEMENT");
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
