package com.java.system.agent.codeintelligence.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import com.java.system.agent.codeintelligence.planning.DiscoverMethodImplementationsExecutionInput;
import com.java.system.agent.answering.application.loop.ContextIssuer;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.AnalysisCandidate;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.candidate.FollowUpCandidate;
import com.java.system.agent.answering.domain.candidate.RouteCandidate;
import com.java.system.agent.answering.domain.candidate.SemanticTargetCandidate;
import com.java.system.agent.answering.domain.evidence.EvidenceRef;
import com.java.system.agent.answering.domain.evidence.SemanticTarget;
import com.java.system.agent.answering.domain.evidence.SemanticTargetKind;
import com.java.system.agent.answering.domain.evidence.SourceRange;
import com.java.system.agent.answering.domain.observation.CapabilityObservation;
import com.java.system.agent.answering.domain.observation.ObservationCode;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.out.CapabilityExecutionContractException;
import com.java.system.agent.answering.port.out.CapabilityExecutionFailureCode;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.answering.port.out.RepositoryDescriptor;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.codeintelligence.planning.DiscoverTypeMembersExecutionInput;
import com.java.system.agent.codeintelligence.planning.FindInternalReferencesExecutionInput;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Java Semantic Service 成功與 error DTO 的 deterministic answering mapping 測試
 */
class JavaSemanticResultMapperTest {

    private static final String REVISION = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final RepositoryId REPOSITORY_ID = new RepositoryId("orders");
    private static final RepositoryRevision REPOSITORY_REVISION = new RepositoryRevision(REVISION);

    @Test
    void projectsDiscoveryConceptResponseDecodedFromTheProviderWireContract() throws Exception {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.DiscoverConceptsResponse response = new ObjectMapper().findAndRegisterModules().readValue("""
                {"repoId":"orders","analyzedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","normalizedTerms":["order"],"searchedKinds":["TYPE"],"supportedKinds":["TYPE"],"limitations":[],"candidates":[],"page":{"offset":0,"limit":10,"returnedCount":0,"totalCount":0,"hasMore":false},"coverage":{"status":"COMPLETE","scannedFileCount":1,"extractedFileCount":1,"syntaxFailedFileCount":0},"issueSummaries":[],"availableFollowUps":[],"unavailableFollowUps":[]}
                """, SemanticDtos.DiscoverConceptsResponse.class);

        CapabilityExecutionResult.Succeeded result = (CapabilityExecutionResult.Succeeded) mapper.discoverConcepts(
                REPOSITORY_ID, REPOSITORY_REVISION, response);

        assertThat(result.discoveredCandidates()).isEmpty();
        assertThat(result.evidence()).isEmpty();
        assertThat(result.observations()).extracting(CapabilityObservation::code)
                .containsExactly(ObservationCode.UNSUPPORTED_CLAIM);
    }

    @Test
    void preservesUnavailableFollowUpAsTheOnlyAuthorityForACompleteEmptyConceptSearch() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.DiscoverConceptsResponse response = new SemanticDtos.DiscoverConceptsResponse(
                "orders",
                REVISION,
                List.of("buy now pay later"),
                List.of("TYPE"),
                List.of("TYPE"),
                List.of(),
                List.of(),
                new SemanticDtos.PageResponse(0, 10, 0, 0, false),
                new SemanticDtos.ConceptCoverageResponse("COMPLETE", 1, 1, 0),
                List.of(),
                List.of(),
                List.of(new SemanticDtos.UnavailableFollowUpResponse(
                        "SEARCH_INCOMPLETE", "FIX_SOURCE_OR_RETRY")));

        CapabilityExecutionResult.Succeeded result = (CapabilityExecutionResult.Succeeded)
                mapper.discoverConcepts(REPOSITORY_ID, REPOSITORY_REVISION, response);

        assertThat(result.observations()).extracting(CapabilityObservation::code)
                .containsExactly(ObservationCode.UNADDRESSED_PART);
    }

    @Test
    void keepsResolvedApiMetadataWhenEntryPointDescriptionExceedsEvidenceBound() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.MethodTarget target = new SemanticDtos.MethodTarget("src/Orders.java", "com.example", "Orders",
                "create", List.of("CreateOrder"));
        String description = "x".repeat(1_100);
        SemanticDtos.EntryPointsResponse response = new SemanticDtos.EntryPointsResponse("orders", REVISION, List.of(
                new SemanticDtos.EntryPointClassResponse(sourceType("Orders"), "Order entry points",
                List.of("/orders"), List.of(new SemanticDtos.ApiEntryPointMethodResponse("create", description, "API",
                "/orders", List.of("POST"), List.of("Creates an order"), resolved(target))))));

        CapabilityExecutionResult.Succeeded result = (CapabilityExecutionResult.Succeeded) mapper.listEntryPoints(
                REPOSITORY_ID, REPOSITORY_REVISION, response);

        assertThat(result.evidence()).singleElement().extracting(EvidenceRef::content)
                .asString().contains("url=/orders", "httpMethods=POST", "swagger=Creates an order");
    }

    @Test
    void mapsProviderMetadataToRevisionPinnedEvidence() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        RepositoryRevision revision = REPOSITORY_REVISION;
        SemanticDtos.SourceTypeIdentityPayload sourceType = new SemanticDtos.SourceTypeIdentityPayload(
                new SemanticDtos.JavaTypeIdentityPayload("com.example", "Orders"), "src/Orders.java");
        SemanticDtos.MethodTarget apiTarget = new SemanticDtos.MethodTarget("src/Orders.java", "com.example",
                "Orders", "create", List.of("CreateOrder"));
        SemanticDtos.MethodTarget mqTarget = new SemanticDtos.MethodTarget("src/Orders.java", "com.example",
                "Orders", "consume", List.of("OrderCreated"));
        SemanticDtos.MethodTarget scheduleTarget = new SemanticDtos.MethodTarget("src/Orders.java", "com.example",
                "Orders", "reconcile", List.of());
        SemanticDtos.MethodTargetPayload requestedTarget = new SemanticDtos.MethodTargetPayload(sourceType, "find",
                List.of("java.lang.String"));
        SemanticDtos.MethodTargetPayload implementationTarget = new SemanticDtos.MethodTargetPayload(
                new SemanticDtos.SourceTypeIdentityPayload(new SemanticDtos.JavaTypeIdentityPayload("com.example",
                        "OrderLookupService"), "src/OrderLookupService.java"), "find", List.of("java.lang.String"));
        SemanticDtos.TextRangePayload declarationRange = textRange(0, 0, 0, 1);
        SemanticDtos.TextRangePayload firstOccurrence = textRange(8, 2, 8, 8);
        SemanticDtos.TextRangePayload secondOccurrence = textRange(12, 6, 12, 12);

        CapabilityExecutionResult.Succeeded entryPoints = (CapabilityExecutionResult.Succeeded) mapper.listEntryPoints(
                REPOSITORY_ID, revision, new SemanticDtos.EntryPointsResponse("orders",
                REVISION, List.of(new SemanticDtos.EntryPointClassResponse(sourceType("Orders"), "Order entry points",
                List.of("/orders"), List.of(
                new SemanticDtos.ApiEntryPointMethodResponse("create", "Create order", "API", "/orders",
                        List.of("POST"), List.of("Creates an order"), resolved(apiTarget)),
                new SemanticDtos.MqEntryPointMethodResponse("consume", "Consume order", "MQ", "KAFKA",
                        List.of("orders.created"), resolved(mqTarget)),
                new SemanticDtos.ScheduleEntryPointMethodResponse("reconcile", "Reconcile orders", "SCHEDULE",
                        "CRON", "0 * * * * *", resolved(scheduleTarget)))))));
        CapabilityExecutionResult.Succeeded implementations = (CapabilityExecutionResult.Succeeded)
                mapper.discoverMethodImplementations(REPOSITORY_ID, revision,
                new SemanticDtos.DiscoverMethodImplementationsResponse("orders", REVISION, requestedTarget, List.of(
                new SemanticDtos.MethodImplementationCandidateResponse(implementationTarget, true, List.of("orders"),
                        List.of("prod"), List.of())), new SemanticDtos.BoundedResultResponse(10, 1, 1, false),
                new SemanticDtos.MethodImplementationResolutionResponse("COMPLETE", List.of())));
        CapabilityExecutionResult.Succeeded references = (CapabilityExecutionResult.Succeeded) mapper.findInternalReferences(
                REPOSITORY_ID, revision, new SemanticDtos.InternalReferenceFollowUpTarget("TYPE", sourceType),
                new SemanticDtos.FindInternalReferencesResponse("orders", REVISION, "COMPLETE",
                new SemanticDtos.InternalReferenceTargetDeclarationResponse(
                        new SemanticDtos.InternalReferenceFollowUpTarget("TYPE", sourceType), declarationRange, List.of()), 7,
                List.of(new SemanticDtos.ReferenceGroupResponse(
                        new SemanticDtos.InternalReferenceTypeContextResponse("TYPE", sourceType), List.of(
                        new SemanticDtos.ReferenceOccurrenceResponse(firstOccurrence, List.of()),
                        new SemanticDtos.ReferenceOccurrenceResponse(secondOccurrence, List.of())),
                        new SemanticDtos.BoundedResultResponse(10, 2, 7, true), List.of(), List.of())),
                new SemanticDtos.PageResponse(0, 10, 2, 7, true), List.of(), List.of()));

        assertMetadataEvidence(entryPoints.evidence(), 3, revision);
        assertThat(entryPoints.evidence()).extracting(EvidenceRef::semanticTarget)
                .containsExactly(mapper.semanticTarget(apiTarget), mapper.semanticTarget(mqTarget), mapper.semanticTarget(scheduleTarget));
        assertThat(entryPoints.evidence().get(0).content()).contains("url=/orders", "httpMethods=POST",
                "swagger=Creates an order", "kind=API");
        assertThat(entryPoints.evidence().get(1).content()).contains("broker=KAFKA", "destinations=orders.created",
                "kind=MQ");
        assertThat(entryPoints.evidence().get(2).content()).contains("trigger=CRON:0 * * * * *", "kind=SCHEDULE");

        assertMetadataEvidence(implementations.evidence(), 1, revision);
        assertThat(implementations.evidence().getFirst().semanticTarget()).isEqualTo(mapper.semanticTarget(
                new SemanticDtos.MethodTarget("src/OrderLookupService.java", "com.example", "OrderLookupService",
                        "find", List.of("java.lang.String"))));
        assertThat(implementations.evidence().getFirst().content()).contains("requested=src/Orders.java#Orders.find",
                "implementation=src/OrderLookupService.java#OrderLookupService.find", "primary=true", "qualifiers=orders",
                "profiles=prod");

        assertMetadataEvidence(references.evidence(), 3, revision);
        assertThat(references.evidence()).extracting(EvidenceRef::semanticTarget).containsExactly(
                sourceRangeTarget("src/Orders.java", declarationRange), sourceRangeTarget("src/Orders.java", firstOccurrence),
                sourceRangeTarget("src/Orders.java", secondOccurrence));
        assertThat(references.evidence().get(0).content()).contains("reference=declaration", "target=TYPE",
                "range=1:1-1:2", "total=7");
        assertThat(references.evidence().subList(1, 3)).extracting(EvidenceRef::content)
                .allSatisfy(content -> assertThat(content).contains("reference=occurrence", "context=TYPE", "total=7"));
        assertThat(entryPoints.observations()).isEmpty();
        assertThat(implementations.observations()).isEmpty();
        assertThat(references.observations()).extracting(CapabilityObservation::code)
                .containsExactly(ObservationCode.TRUNCATED_CANDIDATES, ObservationCode.TRUNCATED_CANDIDATES);
    }

    @Test
    void doesNotInventEvidenceForUnresolvedEntryPointTarget() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.EntryPointsResponse response = new SemanticDtos.EntryPointsResponse("orders", REVISION, List.of(
                new SemanticDtos.EntryPointClassResponse(sourceType("Orders"), "Order entry points",
                        List.of("/orders"), List.of(new SemanticDtos.ApiEntryPointMethodResponse("create", "Create order",
                                "API", "/orders", List.of("POST"), List.of("Creates an order"),
                                new SemanticDtos.MethodTargetResolutionResponse("UNRESOLVED", null, List.of(),
                                        "SOURCE_BINDING_UNRESOLVED", List.of()))))));

        CapabilityExecutionResult.Succeeded result = (CapabilityExecutionResult.Succeeded) mapper.listEntryPoints(
                REPOSITORY_ID, REPOSITORY_REVISION, response);

        assertThat(result.evidence()).isEmpty();
        assertThat(result.observations()).extracting(CapabilityObservation::code)
                .containsExactly(ObservationCode.UNRESOLVED_CALL);
    }

    @Test
    void preservesProviderIssuedEntryPointFollowUps() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.SourceTypeIdentityPayload sourceType = sourceType("Orders");
        SemanticDtos.MethodTarget target = new SemanticDtos.MethodTarget(sourceType.sourceFile(),
                sourceType.javaType().packageName(), sourceType.javaType().className(), "create", List.of());
        SemanticDtos.MethodTargetPayload followUpTarget = new SemanticDtos.MethodTargetPayload(sourceType, "create", List.of());
        SemanticDtos.AvailableFollowUp followUp = new SemanticDtos.AvailableFollowUp("GET_METHOD_SOURCE",
                new SemanticDtos.FollowUpApi("POST", "/v1/discovery/method-source", "getMethodSource"),
                new SemanticDtos.TargetFollowUpRequest("orders", REVISION, followUpTarget, Optional.empty(),
                        Optional.empty(), Optional.empty()));
        SemanticDtos.EntryPointsResponse response = new SemanticDtos.EntryPointsResponse("orders", REVISION, List.of(
                new SemanticDtos.EntryPointClassResponse(sourceType, "Order entry points", List.of("/orders"), List.of(
                        new SemanticDtos.ApiEntryPointMethodResponse("create", "Create order", "API", "/orders",
                                List.of("POST"), List.of("Creates an order"),
                                new SemanticDtos.MethodTargetResolutionResponse("RESOLVED", target, List.of(),
                                        "RESOLVED_TARGET", List.of(followUp)))))));

        CapabilityExecutionResult.Succeeded result = (CapabilityExecutionResult.Succeeded) mapper.listEntryPoints(
                REPOSITORY_ID, REPOSITORY_REVISION, response);

        assertThat(result.discoveredCandidates()).extracting(AnalysisCandidate::kind)
                .containsExactly(CandidateKind.ROUTE, CandidateKind.SEMANTIC_TARGET, CandidateKind.FOLLOW_UP);
    }

    @Test
    void deduplicatesRepeatedClassFollowUpsAcrossEntryPointMethodsBeforeStrictCapabilityIssuance() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.SourceTypeIdentityPayload sourceType = sourceType("Orders");
        SemanticDtos.AvailableFollowUp typeMembersFollowUp = new SemanticDtos.AvailableFollowUp(
                "GET_TYPE_MEMBERS",
                new SemanticDtos.FollowUpApi("POST", "/v1/discovery/type-members", "discoverTypeMembers"),
                new SemanticDtos.TypeMembersFollowUpRequest("orders", REVISION, sourceType, List.of("FIELD"),
                        Optional.empty(), 0, 50));
        SemanticDtos.EntryPointsResponse response = new SemanticDtos.EntryPointsResponse("orders", REVISION, List.of(
                new SemanticDtos.EntryPointClassResponse(sourceType, "Order entry points", List.of("/orders"), List.of(
                        entryPointMethod(sourceType, "create", "/orders", typeMembersFollowUp),
                        entryPointMethod(sourceType, "find", "/orders/{id}", typeMembersFollowUp)))));

        CapabilityExecutionResult.Succeeded mapped = (CapabilityExecutionResult.Succeeded) mapper.listEntryPoints(
                REPOSITORY_ID, REPOSITORY_REVISION, response);
        ContextIssuer issuer = new ContextIssuer();
        ContextIssuer.CapabilityIssue issued = issuer.issueCapabilityResult(
                new AnalysisRunId("run-duplicate-entry-point-follow-ups"),
                issuer.issueInitial(
                        new AnalysisRunId("run-duplicate-entry-point-follow-ups"),
                        new AnalysisAttemptId("attempt-duplicate-entry-point-follow-ups"),
                        RevisionVector.empty().pin(REPOSITORY_ID, REPOSITORY_REVISION),
                        List.of(new CapabilityPolicy("find", "v1", Set.of(CandidateKind.SEMANTIC_TARGET), 1, 10)),
                        List.of(new RepositoryDescriptor(REPOSITORY_ID, "Orders repository"))),
                mapped,
                Set.of(REPOSITORY_ID));

        assertThat(mapped.discoveredCandidates()).extracting(AnalysisCandidate::kind)
                .containsExactly(CandidateKind.ROUTE, CandidateKind.SEMANTIC_TARGET, CandidateKind.FOLLOW_UP,
                        CandidateKind.ROUTE, CandidateKind.SEMANTIC_TARGET);
        assertThat(mapped.discoveredCandidates().stream()
                .filter(FollowUpCandidate.class::isInstance)
                .map(FollowUpCandidate.class::cast)
                .map(FollowUpCandidate::targetCapabilityName))
                .containsExactly("codebase_discover_type_members");
        assertThat(issued.resultCandidateHandleValues()).hasSize(5);
    }

    @Test
    void deduplicatesRepeatedTypeMemberFollowUpsBeforeStrictCapabilityIssuance() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.SourceTypeIdentityPayload sourceType = sourceType("Orders");
        SemanticDtos.AvailableFollowUp fieldMembersFollowUp = new SemanticDtos.AvailableFollowUp(
                "GET_TYPE_MEMBERS",
                new SemanticDtos.FollowUpApi("POST", "/v1/discovery/type-members", "discoverTypeMembers"),
                new SemanticDtos.TypeMembersFollowUpRequest("orders", REVISION, sourceType, List.of("FIELD"),
                        Optional.empty(), 0, 50));
        SemanticDtos.MethodTargetPayload firstTarget = new SemanticDtos.MethodTargetPayload(
                sourceType, "create", List.of());
        SemanticDtos.MethodTargetPayload secondTarget = new SemanticDtos.MethodTargetPayload(
                sourceType, "find", List.of());
        SemanticDtos.DiscoverTypeMembersResponse response = new SemanticDtos.DiscoverTypeMembersResponse(
                "orders", REVISION, sourceType, "CLASS", List.of(), List.of(), List.of(),
                List.of(
                        new SemanticDtos.MethodTypeMemberResponse("METHOD", firstTarget,
                                List.of(fieldMembersFollowUp)),
                        new SemanticDtos.MethodTypeMemberResponse("METHOD", secondTarget,
                                List.of(fieldMembersFollowUp))),
                new SemanticDtos.PageResponse(0, 50, 2, 2, false),
                new SemanticDtos.ConceptCoverageResponse("COMPLETE", 1, 1, 0),
                List.of());

        CapabilityExecutionResult.Succeeded mapped = (CapabilityExecutionResult.Succeeded) mapper.discoverTypeMembers(
                REPOSITORY_ID, REPOSITORY_REVISION, response);
        ContextIssuer issuer = new ContextIssuer();
        ContextIssuer.CapabilityIssue issued = issuer.issueCapabilityResult(
                new AnalysisRunId("run-duplicate-type-member-follow-ups"),
                issuer.issueInitial(
                        new AnalysisRunId("run-duplicate-type-member-follow-ups"),
                        new AnalysisAttemptId("attempt-duplicate-type-member-follow-ups"),
                        RevisionVector.empty().pin(REPOSITORY_ID, REPOSITORY_REVISION),
                        List.of(new CapabilityPolicy("find", "v1", Set.of(CandidateKind.SEMANTIC_TARGET), 1, 10)),
                        List.of(new RepositoryDescriptor(REPOSITORY_ID, "Orders repository"))),
                mapped,
                Set.of(REPOSITORY_ID));

        assertThat(mapped.discoveredCandidates()).extracting(AnalysisCandidate::kind)
                .containsExactly(CandidateKind.SEMANTIC_TARGET, CandidateKind.FOLLOW_UP,
                        CandidateKind.SEMANTIC_TARGET);
        assertThat(issued.resultCandidateHandleValues()).hasSize(3);
    }

    @Test
    void projects_value_type_members_as_revision_pinned_declaration_evidence_without_generic_targets() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.SourceTypeIdentityPayload sourceType = sourceType("Order");
        SemanticDtos.TextRangePayload enumRange = textRange(2, 4, 2, 8);
        SemanticDtos.TextRangePayload componentRange = textRange(4, 13, 4, 22);
        SemanticDtos.SourceMemberIdentityPayload.TypeMember enumIdentity =
                new SemanticDtos.SourceMemberIdentityPayload.TypeMember("TYPE", sourceType, "CARD");
        SemanticDtos.SourceMemberIdentityPayload.TypeMember componentIdentity =
                new SemanticDtos.SourceMemberIdentityPayload.TypeMember("TYPE", sourceType, "reference");
        SemanticDtos.AvailableFollowUp enumReferences = internalReferencesFollowUp(enumIdentity);
        SemanticDtos.AvailableFollowUp componentReferences = internalReferencesFollowUp(componentIdentity);
        SemanticDtos.PageResponse page = new SemanticDtos.PageResponse(0, 10, 2, 2, false);
        SemanticDtos.ConceptCoverageResponse coverage = new SemanticDtos.ConceptCoverageResponse("COMPLETE", 1, 1, 0);

        CapabilityExecutionResult.Succeeded result = (CapabilityExecutionResult.Succeeded) mapper.discoverTypeMembers(
                REPOSITORY_ID, REPOSITORY_REVISION, new SemanticDtos.DiscoverTypeMembersResponse("orders", REVISION,
                sourceType, "RECORD", List.of(), List.of(), List.of(), List.of(
                new SemanticDtos.EnumConstantTypeMemberResponse("ENUM_CONSTANT", enumIdentity, enumRange,
                        List.of("Deprecated"), List.of(enumReferences)),
                new SemanticDtos.RecordComponentTypeMemberResponse("RECORD_COMPONENT", componentIdentity, "String",
                        Optional.of("java.lang.String"), componentRange, List.of(), List.of(componentReferences))),
                page, coverage, List.of()));

        assertThat(result.discoveredCandidates()).hasSize(2).allMatch(FollowUpCandidate.class::isInstance);
        assertThat(result.evidence()).extracting(EvidenceRef::repositoryId).containsOnly(REPOSITORY_ID);
        assertThat(result.evidence()).extracting(EvidenceRef::repositoryRevision).containsOnly(REPOSITORY_REVISION);
        assertThat(result.evidence()).extracting(evidence -> evidence.semanticTarget().sourceRange().orElseThrow().startLine())
                .containsExactly(3, 5);
        assertThat(result.evidence()).extracting(EvidenceRef::content).containsExactly(
                "typeMember; kind=ENUM_CONSTANT; owner=src/Order.java#Order; member=CARD; range=3:5-3:9",
                "typeMember; kind=RECORD_COMPONENT; owner=src/Order.java#Order; member=reference; "
                        + "writtenType=String; resolvedType=java.lang.String; range=5:14-5:23");
        assertThat(result.observations()).isEmpty();
    }

    @Test
    void acceptsProviderContinuationsForValueTypeMemberKinds() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.SourceTypeIdentityPayload sourceType = sourceType("Order");
        SemanticDtos.AvailableFollowUp continuation = new SemanticDtos.AvailableFollowUp(
                "DISCOVER_TYPE_MEMBERS",
                new SemanticDtos.FollowUpApi("POST", "/v1/discovery/type-members", "discoverTypeMembers"),
                new SemanticDtos.TypeMembersFollowUpRequest("orders", REVISION, sourceType,
                        List.of("ENUM_CONSTANT", "RECORD_COMPONENT"), Optional.empty(), 10, 10));

        CapabilityExecutionResult.Succeeded result = (CapabilityExecutionResult.Succeeded) mapper.discoverTypeMembers(
                REPOSITORY_ID, REPOSITORY_REVISION, new SemanticDtos.DiscoverTypeMembersResponse("orders", REVISION,
                sourceType, "RECORD", List.of(), List.of(), List.of(), List.of(),
                new SemanticDtos.PageResponse(0, 10, 10, 20, true),
                new SemanticDtos.ConceptCoverageResponse("COMPLETE", 1, 1, 0), List.of(continuation)));

        assertThat(result.discoveredCandidates()).singleElement().isInstanceOf(FollowUpCandidate.class);
    }

    @Test
    void deduplicatesRepeatedValueTypeMemberEvidenceBeforeStrictCapabilityIssuance() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.SourceTypeIdentityPayload sourceType = sourceType("Order");
        SemanticDtos.SourceMemberIdentityPayload.TypeMember identity =
                new SemanticDtos.SourceMemberIdentityPayload.TypeMember("TYPE", sourceType, "CARD");
        SemanticDtos.EnumConstantTypeMemberResponse repeated = new SemanticDtos.EnumConstantTypeMemberResponse(
                "ENUM_CONSTANT", identity, textRange(2, 4, 2, 8), List.of(), List.of());

        CapabilityExecutionResult.Succeeded result = (CapabilityExecutionResult.Succeeded) mapper.discoverTypeMembers(
                REPOSITORY_ID, REPOSITORY_REVISION, new SemanticDtos.DiscoverTypeMembersResponse("orders", REVISION,
                sourceType, "ENUM", List.of(), List.of(), List.of(), List.of(repeated, repeated),
                new SemanticDtos.PageResponse(0, 10, 2, 2, false),
                new SemanticDtos.ConceptCoverageResponse("COMPLETE", 1, 1, 0), List.of()));

        assertThat(result.evidence()).hasSize(1);
    }

    @Test
    void marks_only_complete_unpaged_empty_type_member_results_as_unsupported_claims() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.SourceTypeIdentityPayload sourceType = sourceType("Order");
        SemanticDtos.ConceptCoverageResponse completeCoverage = new SemanticDtos.ConceptCoverageResponse("COMPLETE", 1, 1, 0);

        CapabilityExecutionResult.Succeeded completeEmpty = (CapabilityExecutionResult.Succeeded) mapper.discoverTypeMembers(
                REPOSITORY_ID, REPOSITORY_REVISION, new SemanticDtos.DiscoverTypeMembersResponse("orders", REVISION,
                sourceType, "RECORD", List.of(), List.of(), List.of(), List.of(),
                new SemanticDtos.PageResponse(0, 10, 0, 0, false), completeCoverage, List.of()));
        CapabilityExecutionResult.Succeeded pagedEmpty = (CapabilityExecutionResult.Succeeded) mapper.discoverTypeMembers(
                REPOSITORY_ID, REPOSITORY_REVISION, new SemanticDtos.DiscoverTypeMembersResponse("orders", REVISION,
                sourceType, "RECORD", List.of(), List.of(), List.of(), List.of(),
                new SemanticDtos.PageResponse(0, 10, 0, 1, true), completeCoverage, List.of()));

        assertThat(completeEmpty.observations()).extracting(CapabilityObservation::code)
                .containsExactly(ObservationCode.UNSUPPORTED_CLAIM);
        assertThat(pagedEmpty.observations()).extracting(CapabilityObservation::code)
                .containsExactly(ObservationCode.TRUNCATED_CANDIDATES);
    }

    @Test
    void projectsGraphEdgeImplementationFollowUpsWithoutInferringThem() throws Exception {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.MethodTargetPayload abstractTarget = methodTargetPayload();
        String target = """
                {"sourceType":{"javaType":{"packageName":"com.example","className":"Orders"},"sourceFile":"src/Orders.java"},"methodName":"find","parameterTypes":[]}
                """;
        String sourceRange = """
                {"sourceFile":"src/Orders.java","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}}
                """;
        String implementationFollowUp = """
                {"operation":"DISCOVER_METHOD_IMPLEMENTATIONS","api":{"method":"POST","path":"/v1/discovery/method-implementations","operationId":"discoverMethodImplementations"},"request":{"repoId":"orders","expectedRevision":"%s","declarationTarget":%s}}
                """.formatted(REVISION, target);
        SemanticDtos.OutgoingCallGraphResponse response = new ObjectMapper().findAndRegisterModules().readValue("""
                {"status":"SUCCESS","analyzedRevision":"%s","rootNodeId":"root","traversal":{"requestedDepth":1,"expandedNodeCount":1,"nodeBudget":10,"rootDirectCallsComplete":true,"limitReason":"NONE"},"nodes":[{"nodeId":"root","target":%s,"externalSymbol":null,"contentState":"FULL_SOURCE","traversalState":"EXPANDED","dispatchKind":"SYNCHRONOUS","declarationRange":null,"availableFollowUps":[]}],"edges":[{"callerNodeId":"root","calleeNodeId":"implementation","callSite":%s,"callExpression":"delegate()","resolutionStrategy":"JDT_CALL_HIERARCHY","category":"RESOLVED_ANALYZABLE","evidence":[],"availableFollowUps":[%s]},{"callerNodeId":"root","calleeNodeId":"ordinary","callSite":%s,"callExpression":"find()","resolutionStrategy":"JDT_CALL_HIERARCHY","category":"RESOLVED_ANALYZABLE","evidence":[],"availableFollowUps":[]}],"warnings":[],"errors":[]}
                """.formatted(REVISION, target, sourceRange, implementationFollowUp, sourceRange),
                SemanticDtos.OutgoingCallGraphResponse.class);
        CanonicalCapabilityPayloadCodec codec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());

        CapabilityExecutionResult.Succeeded result = (CapabilityExecutionResult.Succeeded) mapper.outgoingCallGraph(
                REPOSITORY_ID, REPOSITORY_REVISION, abstractTarget, response);

        List<FollowUpCandidate> followUps = result.discoveredCandidates().stream()
                .filter(FollowUpCandidate.class::isInstance)
                .map(FollowUpCandidate.class::cast)
                .toList();
        assertThat(followUps).extracting(FollowUpCandidate::targetCapabilityName)
                .containsExactly("codebase_discover_method_implementations");
        assertThat(followUps).extracting(FollowUpCandidate::repositoryId).containsExactly(REPOSITORY_ID);
        assertThat(followUps).extracting(FollowUpCandidate::analyzedRevision).containsExactly(REPOSITORY_REVISION);
        assertThat(codec.decode(followUps.getFirst().payload(), DiscoverMethodImplementationsExecutionInput.class))
                .isEqualTo(new DiscoverMethodImplementationsExecutionInput(abstractTarget));
    }

    @Test
    void deduplicatesRepeatedGraphEdgeFollowUpsBeforeStrictCapabilityIssuance() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.MethodTargetPayload requestedTarget = methodTargetPayload();
        SemanticDtos.MethodTarget rootTarget = new SemanticDtos.MethodTarget(
                requestedTarget.sourceType().sourceFile(),
                requestedTarget.sourceType().javaType().packageName(),
                requestedTarget.sourceType().javaType().className(),
                requestedTarget.methodName(),
                requestedTarget.parameterTypes());
        SemanticDtos.AvailableFollowUp repeatedImplementationFollowUp = new SemanticDtos.AvailableFollowUp(
                "DISCOVER_METHOD_IMPLEMENTATIONS",
                new SemanticDtos.FollowUpApi("POST", "/v1/discovery/method-implementations",
                        "discoverMethodImplementations"),
                new SemanticDtos.DiscoverMethodImplementationsFollowUpRequest("orders", REVISION, requestedTarget));
        SemanticDtos.AvailableFollowUp methodSourceFollowUp = new SemanticDtos.AvailableFollowUp(
                "GET_METHOD_SOURCE",
                new SemanticDtos.FollowUpApi("POST", "/v1/discovery/method-source", "getMethodSource"),
                new SemanticDtos.TargetFollowUpRequest("orders", REVISION, requestedTarget,
                        Optional.empty(), Optional.empty(), Optional.empty()));
        SemanticDtos.OutgoingCallGraphResponse response = new SemanticDtos.OutgoingCallGraphResponse(
                "SUCCESS", REVISION, "root", new SemanticDtos.GraphTraversal(1, 1, 10, true, "NONE"),
                List.of(new SemanticDtos.GraphNode("root", rootTarget, null, "FULL_SOURCE", "EXPANDED",
                        "SYNCHRONOUS", null, List.of())),
                List.of(
                        graphEdge("implementation-first", 1, repeatedImplementationFollowUp),
                        graphEdge("method-source", 2, methodSourceFollowUp),
                        graphEdge("implementation-second", 3, repeatedImplementationFollowUp)),
                List.of(), List.of());

        CapabilityExecutionResult.Succeeded mapped = (CapabilityExecutionResult.Succeeded) mapper.outgoingCallGraph(
                REPOSITORY_ID, REPOSITORY_REVISION, requestedTarget, response);
        ContextIssuer issuer = new ContextIssuer();
        ContextIssuer.CapabilityIssue issued = issuer.issueCapabilityResult(
                new AnalysisRunId("run-duplicate-follow-ups"),
                issuer.issueInitial(
                        new AnalysisRunId("run-duplicate-follow-ups"),
                        new AnalysisAttemptId("attempt-duplicate-follow-ups"),
                        RevisionVector.empty().pin(REPOSITORY_ID, REPOSITORY_REVISION),
                        List.of(new CapabilityPolicy("find", "v1", Set.of(CandidateKind.SEMANTIC_TARGET), 1, 10)),
                        List.of(new RepositoryDescriptor(REPOSITORY_ID, "Orders repository"))),
                mapped,
                Set.of(REPOSITORY_ID));

        assertThat(mapped.discoveredCandidates().stream()
                .filter(FollowUpCandidate.class::isInstance)
                .map(FollowUpCandidate.class::cast)
                .map(FollowUpCandidate::targetCapabilityName))
                .containsExactly("codebase_discover_method_implementations", "codebase_get_method_source");
        assertThat(issued.context().issuedCandidates().values().stream()
                .map(issuedCandidate -> issuedCandidate.candidate())
                .filter(FollowUpCandidate.class::isInstance)
                .map(FollowUpCandidate.class::cast)
                .map(FollowUpCandidate::targetCapabilityName))
                .containsExactly("codebase_discover_method_implementations", "codebase_get_method_source");
        assertThat(issued.resultCandidateHandleValues()).hasSize(3);
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
        String methodMemberFollowUp = """
                {"operation":"GET_TYPE_MEMBERS","api":{"method":"POST","path":"/v1/discovery/type-members","operationId":"discoverTypeMembers"},"request":{"repoId":"orders","expectedRevision":"%s","sourceType":%s,"memberKinds":["FIELD"],"offset":0,"limit":50}}
                """.formatted(REVISION, sourceType);
        String fieldMemberFollowUp = """
                {"operation":"FIND_INTERNAL_REFERENCES","api":{"method":"POST","path":"/v1/discovery/internal-references","operationId":"findInternalReferences"},"request":{"repoId":"orders","expectedRevision":"%s","target":{"kind":"MEMBER","identity":{"scope":"TYPE","ownerType":%s,"name":"id"}},"offset":0,"limit":20}}
                """.formatted(REVISION, sourceType);
        String methodMemberFollowUps = "[" + methodMemberFollowUp + "]";
        String fieldMemberFollowUps = "[" + fieldMemberFollowUp + "]";
        String concept = """
                {"identity":{"kind":"METHOD","target":%s},"displayValue":"Orders.find","matchedTerms":["order"],"authority":"SYNTAX_RESOLVED","evidence":[{"identity":{"kind":"METHOD","target":%s}}],"availableFollowUps":%s}
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
                """.formatted(REVISION, sourceType, target, methodMemberFollowUps, sourceType, fieldMemberFollowUps, memberPage),
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

        assertThat(((CapabilityExecutionResult.Succeeded) mapper.discoverConcepts(REPOSITORY_ID, REPOSITORY_REVISION,
                concepts)).discoveredCandidates()).hasSize(3);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.resolveConcept(REPOSITORY_ID, REPOSITORY_REVISION,
                resolvedConcept)).discoveredCandidates()).hasSize(3);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.discoverEventListeners(REPOSITORY_ID,
                REPOSITORY_REVISION, listeners)).discoveredCandidates()).hasSize(2);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.discoverMethodImplementations(REPOSITORY_ID,
                REPOSITORY_REVISION, implementations)).discoveredCandidates()).hasSize(2);
        CapabilityExecutionResult.Succeeded memberResult = (CapabilityExecutionResult.Succeeded) mapper.discoverTypeMembers(
                REPOSITORY_ID, REPOSITORY_REVISION, members);
        List<FollowUpCandidate> memberFollowUps = memberResult.discoveredCandidates().stream()
                .filter(FollowUpCandidate.class::isInstance)
                .map(FollowUpCandidate.class::cast)
                .toList();
        CanonicalCapabilityPayloadCodec payloadCodec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        DiscoverTypeMembersExecutionInput typeMembersInput = payloadCodec.decode(memberFollowUps.get(0).payload(),
                DiscoverTypeMembersExecutionInput.class);
        FindInternalReferencesExecutionInput internalReferencesInput = payloadCodec.decode(memberFollowUps.get(1).payload(),
                FindInternalReferencesExecutionInput.class);

        assertThat(memberResult.discoveredCandidates()).hasSize(3);
        assertThat(memberFollowUps).extracting(FollowUpCandidate::targetCapabilityName).containsExactly(
                "codebase_discover_type_members", "codebase_find_internal_references");
        assertThat(memberFollowUps).extracting(FollowUpCandidate::repositoryId).containsOnly(REPOSITORY_ID);
        assertThat(memberFollowUps).extracting(FollowUpCandidate::analyzedRevision).containsOnly(REPOSITORY_REVISION);
        assertThat(memberFollowUps).extracting(FollowUpCandidate::targetCapabilityVersion).containsOnly("v1");
        assertThat(memberFollowUps).extracting(candidate -> candidate.payload().value()).allSatisfy(
                payload -> assertThat(payload).isNotBlank());
        assertThat(typeMembersInput.sourceType()).isEqualTo(objectMapper.readValue(sourceType,
                SemanticDtos.SourceTypeIdentityPayload.class));
        assertThat(typeMembersInput.memberKinds()).containsExactly("FIELD");
        assertThat(typeMembersInput.namePrefix()).isEmpty();
        assertThat(typeMembersInput.offset()).isZero();
        assertThat(typeMembersInput.limit()).isEqualTo(50);
        assertThat(internalReferencesInput.target().kind()).isEqualTo("MEMBER");
        assertThat(internalReferencesInput.target().identity()).isInstanceOf(
                SemanticDtos.SourceMemberIdentityPayload.TypeMember.class);
        SemanticDtos.SourceMemberIdentityPayload.TypeMember fieldIdentity =
                (SemanticDtos.SourceMemberIdentityPayload.TypeMember) internalReferencesInput.target().identity();
        assertThat(fieldIdentity.ownerType()).isEqualTo(objectMapper.readValue(sourceType,
                SemanticDtos.SourceTypeIdentityPayload.class));
        assertThat(fieldIdentity.name()).isEqualTo("id");
        assertThat(internalReferencesInput.offset()).isZero();
        assertThat(internalReferencesInput.limit()).isEqualTo(20);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.findInternalReferences(REPOSITORY_ID,
                REPOSITORY_REVISION, references.targetDeclaration().target(), references)).discoveredCandidates()).hasSize(3);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.getEvidenceSource(REPOSITORY_ID, REPOSITORY_REVISION,
                evidence)).evidence()).hasSize(1);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.getMethodSource(REPOSITORY_ID, REPOSITORY_REVISION,
                methodSource)).evidence()).hasSize(1);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.getSourceSegment(REPOSITORY_ID, REPOSITORY_REVISION,
                sourceSegment)).evidence()).hasSize(1);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.resolveSourceSymbol(REPOSITORY_ID, REPOSITORY_REVISION,
                sourceSymbols)).discoveredCandidates()).hasSize(8);
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

        assertThat(((CapabilityExecutionResult.Succeeded) mapper.resolveConcept(REPOSITORY_ID, REPOSITORY_REVISION,
                new SemanticDtos.ResolveConceptResponse("orders", REVISION, conceptCandidate()))).discoveredCandidates()).hasSize(1);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.discoverEventListeners(REPOSITORY_ID, REPOSITORY_REVISION,
                new SemanticDtos.DiscoverEventListenersResponse("orders", REVISION, "com.example.Event", List.of(
                        new SemanticDtos.EventListenerCandidateResponse(target, List.of(
                                new SemanticDtos.ListenerAnnotationEvidenceResponse("EVENT_LISTENER", "RESOLVED_IDENTITY")),
                                location.range(), List.of())), page, List.of(), List.of())))
                .discoveredCandidates()).hasSize(1);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.discoverMethodImplementations(REPOSITORY_ID,
                REPOSITORY_REVISION,
                new SemanticDtos.DiscoverMethodImplementationsResponse("orders", REVISION, target, List.of(
                        new SemanticDtos.MethodImplementationCandidateResponse(target, true, List.of(), List.of(), List.of())),
                        new SemanticDtos.BoundedResultResponse(10, 1, 1, false),
                        new SemanticDtos.MethodImplementationResolutionResponse("COMPLETE", List.of())))).discoveredCandidates()).hasSize(1);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.discoverTypeMembers(REPOSITORY_ID, REPOSITORY_REVISION,
                new SemanticDtos.DiscoverTypeMembersResponse("orders", REVISION, target.sourceType(), "CLASS", List.of(),
                        List.of(), List.of(), List.of(new SemanticDtos.MethodTypeMemberResponse("METHOD", target, List.of())),
                        page, coverage, List.of())))
                .discoveredCandidates()).hasSize(1);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.findInternalReferences(REPOSITORY_ID, REPOSITORY_REVISION,
                new SemanticDtos.InternalReferenceFollowUpTarget("TYPE", target.sourceType()),
                new SemanticDtos.FindInternalReferencesResponse("orders", REVISION, "COMPLETE",
                        new SemanticDtos.InternalReferenceTargetDeclarationResponse(
                        new SemanticDtos.InternalReferenceFollowUpTarget("TYPE", target.sourceType()), location.range(), List.of()),
                        0, List.of(new SemanticDtos.ReferenceGroupResponse(new SemanticDtos.InternalReferenceTypeContextResponse("TYPE",
                                target.sourceType()), List.of(), new SemanticDtos.BoundedResultResponse(10, 0, 0, false),
                                List.of(), List.of())), page, List.of(), List.of()))).discoveredCandidates()).hasSize(1);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.getMethodSource(REPOSITORY_ID, REPOSITORY_REVISION,
                new SemanticDtos.MethodSourceResponse("orders", REVISION, location, segment, List.of()))).evidence())
                .extracting(EvidenceRef::artifactRef).containsExactly(JavaSemanticArtifactDigest.fromContent("class Orders {}"));
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.getSourceSegment(REPOSITORY_ID, REPOSITORY_REVISION,
                new SemanticDtos.SourceSegmentResponse("orders", REVISION, segment, false, List.of()))).evidence()).hasSize(1);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.getEvidenceSource(REPOSITORY_ID, REPOSITORY_REVISION,
                new SemanticDtos.EvidenceSourceResponse("orders", REVISION, evidenceIdentity(), location, segment, List.of()))).evidence())
                .hasSize(1);
        assertThat(((CapabilityExecutionResult.Succeeded) mapper.resolveSourceSymbol(REPOSITORY_ID, REPOSITORY_REVISION,
                new SemanticDtos.ResolveSourceSymbolResponse("orders", REVISION, "RESOLVED", List.of(),
                        new SemanticDtos.BoundedResultResponse(10, 0, 0, false), List.of(
                        new SemanticDtos.MethodSourceSymbolCandidateResponse("METHOD", target, location.range(),
                                location.range(), 1, List.of())), List.of()))).discoveredCandidates()).hasSize(1);
    }

    @Test
    void projectsDiscoveryUncertaintyAndSourceLocationsWithoutPublishingSourceBodies() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.MethodTargetPayload target = methodTargetPayload();
        SemanticDtos.SourceRangePayload location = sourceRange();
        SemanticDtos.PageResponse partialPage = new SemanticDtos.PageResponse(0, 10, 1, 2, true);
        SemanticDtos.ConceptCoverageResponse partialCoverage = new SemanticDtos.ConceptCoverageResponse("PARTIAL", 2, 1, 1);
        SemanticDtos.SourceMemberIdentityPayload.TypeMember field = new SemanticDtos.SourceMemberIdentityPayload.TypeMember(
                "TYPE", target.sourceType(), "id");

        CapabilityExecutionResult.Succeeded concepts = (CapabilityExecutionResult.Succeeded) mapper.discoverConcepts(
                REPOSITORY_ID, REPOSITORY_REVISION, new SemanticDtos.DiscoverConceptsResponse("orders", REVISION,
                List.of("order"), List.of("TYPE"), List.of("TYPE"), List.of("SOURCE_BODY_NOT_SEARCHED"), List.of(),
                partialPage, partialCoverage, List.of(new SemanticDtos.IssueSummaryResponse("MQ_DESTINATION_UNRESOLVED", 2)),
                List.of(), List.of(new SemanticDtos.UnavailableFollowUpResponse("SEARCH_INCOMPLETE", "FIX_SOURCE_OR_RETRY"))));
        CapabilityExecutionResult.Succeeded listeners = (CapabilityExecutionResult.Succeeded) mapper.discoverEventListeners(
                REPOSITORY_ID, REPOSITORY_REVISION, new SemanticDtos.DiscoverEventListenersResponse("orders", REVISION,
                "com.example.Event", List.of(), partialPage, List.of(new SemanticDtos.ListenerObservationSummaryResponse(
                "LISTENER_TARGET_UNRESOLVED", 2, List.of(location))), List.of()));
        CapabilityExecutionResult.Succeeded implementations = (CapabilityExecutionResult.Succeeded)
                mapper.discoverMethodImplementations(REPOSITORY_ID, REPOSITORY_REVISION,
                new SemanticDtos.DiscoverMethodImplementationsResponse("orders", REVISION, target, List.of(),
                new SemanticDtos.BoundedResultResponse(10, 1, 2, true),
                new SemanticDtos.MethodImplementationResolutionResponse("PARTIAL", List.of(
                new SemanticDtos.IssueSummaryResponse("CANONICAL_TARGET_UNRESOLVED", 2)))));
        CapabilityExecutionResult.Succeeded members = (CapabilityExecutionResult.Succeeded) mapper.discoverTypeMembers(
                REPOSITORY_ID, REPOSITORY_REVISION, new SemanticDtos.DiscoverTypeMembersResponse("orders", REVISION,
                target.sourceType(), "CLASS", List.of(), List.of(), List.of(), List.of(
                new SemanticDtos.FieldTypeMemberResponse("FIELD", field, "long", Optional.empty(), List.of(),
                        List.of("FIELD_USAGE_NOT_INDEXED"), List.of())), partialPage, partialCoverage, List.of()));
        CapabilityExecutionResult.Succeeded references = (CapabilityExecutionResult.Succeeded) mapper.findInternalReferences(
                REPOSITORY_ID, REPOSITORY_REVISION, new SemanticDtos.InternalReferenceFollowUpTarget("TYPE", target.sourceType()),
                new SemanticDtos.FindInternalReferencesResponse("orders", REVISION,
                "PARTIAL", new SemanticDtos.InternalReferenceTargetDeclarationResponse(
                new SemanticDtos.InternalReferenceFollowUpTarget("TYPE", target.sourceType()), location.range(), List.of()), 2,
                List.of(new SemanticDtos.ReferenceGroupResponse(new SemanticDtos.InternalReferenceTypeContextResponse("TYPE",
                target.sourceType()), List.of(new SemanticDtos.ReferenceOccurrenceResponse(location.range(), List.of())),
                new SemanticDtos.BoundedResultResponse(10, 1, 2, true), List.of(), List.of(
                new SemanticDtos.UnavailableFollowUpResponse("SEARCH_INCOMPLETE", "FIX_SOURCE_OR_RETRY")))), partialPage,
                List.of(new SemanticDtos.IssueSummaryResponse("REFERENCE_CONTEXT_UNRESOLVED", 2)), List.of()));
        CapabilityExecutionResult.Succeeded symbols = (CapabilityExecutionResult.Succeeded) mapper.resolveSourceSymbol(
                REPOSITORY_ID, REPOSITORY_REVISION, new SemanticDtos.ResolveSourceSymbolResponse("orders", REVISION,
                "UNRESOLVED_BINDING", List.of(), new SemanticDtos.BoundedResultResponse(10, 1, 2, true), List.of(
                new SemanticDtos.VariableLikeSourceSymbolCandidateResponse("FIELD", field,
                new SemanticDtos.DeclaredTypeResponse("long", Optional.empty()), location.range(), location.range(), 2,
                List.of())), List.of(new SemanticDtos.SourceSymbolIssueSummaryResponse("SOURCE_BINDING_UNRESOLVED", 2))));

        assertThat(concepts.observations()).extracting(CapabilityObservation::code).contains(
                ObservationCode.TRUNCATED_CANDIDATES, ObservationCode.MISSING_SOURCE, ObservationCode.UNSUPPORTED_CLAIM,
                ObservationCode.UNRESOLVED_CALL, ObservationCode.UNADDRESSED_PART);
        assertThat(listeners.observations()).extracting(CapabilityObservation::code).contains(ObservationCode.UNRESOLVED_CALL);
        assertThat(implementations.observations()).extracting(CapabilityObservation::code).contains(
                ObservationCode.TRUNCATED_CANDIDATES, ObservationCode.UNRESOLVED_CALL);
        assertThat(members.observations()).extracting(CapabilityObservation::code).contains(
                ObservationCode.MISSING_SOURCE, ObservationCode.UNSUPPORTED_CLAIM);
        assertThat(references.discoveredCandidates()).hasSize(2).allMatch(SemanticTargetCandidate.class::isInstance);
        assertThat(references.observations()).extracting(CapabilityObservation::code).contains(
                ObservationCode.TRUNCATED_CANDIDATES, ObservationCode.UNRESOLVED_CALL, ObservationCode.UNADDRESSED_PART);
        assertThat(symbols.discoveredCandidates()).hasSize(2).allMatch(SemanticTargetCandidate.class::isInstance);
        assertThat(symbols.observations()).extracting(CapabilityObservation::code).contains(
                ObservationCode.TRUNCATED_CANDIDATES, ObservationCode.UNRESOLVED_CALL);
        assertThat(symbols.observations()).extracting(CapabilityObservation::description)
                .noneMatch(description -> description.contains("initializerSource"));
    }

    @Test
    void rejectsDiscoveryResponseScopeThatDoesNotMatchTheSelectedCandidate() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.ResolveConceptResponse response = new SemanticDtos.ResolveConceptResponse("orders", REVISION,
                conceptCandidate());

        assertThatThrownBy(() -> mapper.resolveConcept(new RepositoryId("billing"), new RepositoryRevision(REVISION), response))
                .isInstanceOf(CapabilityExecutionContractException.class);
        assertThatThrownBy(() -> mapper.resolveConcept(new RepositoryId("orders"),
                new RepositoryRevision("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), response))
                .isInstanceOf(CapabilityExecutionContractException.class);
    }

    @Test
    void rejectsConceptDetailsThatDoNotMatchTheConceptIdentityKind() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.ConceptCandidateResponse candidate = new SemanticDtos.ConceptCandidateResponse(
                conceptCandidate().identity(), "Orders.find", List.of("order"), "SYNTAX_RESOLVED",
                Optional.of(new SemanticDtos.FieldConceptCandidateDetailsResponse("FIELD",
                        new SemanticDtos.PrimitiveFieldTypeReferenceResponse("PRIMITIVE", "int"))),
                List.of(), List.of());

        assertThatThrownBy(() -> mapper.resolveConcept(REPOSITORY_ID, REPOSITORY_REVISION,
                new SemanticDtos.ResolveConceptResponse("orders", REVISION, candidate)))
                .isInstanceOf(CapabilityExecutionContractException.class);
    }

    @Test
    void rejectsMapperStatementDetailsWhoseStatementDoesNotMatchTheConceptIdentity() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.MapperStatementKeyPayload identityKey = new SemanticDtos.MapperStatementKeyPayload("orders", "find");
        SemanticDtos.MapperStatementKeyPayload mappingKey = new SemanticDtos.MapperStatementKeyPayload("orders", "load");
        SemanticDtos.ConceptFollowUpIdentity identity = new SemanticDtos.ConceptFollowUpIdentity("MAPPER_STATEMENT",
                Optional.empty(), Optional.empty(), Optional.of(identityKey), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        SemanticDtos.MapperStatementMappingResponse mapping = new SemanticDtos.ResolvedMapperStatementMappingResponse(
                mappingKey, "RESOLVED", List.of(new SemanticDtos.MapperSourceMethodCandidateResponse(methodTargetPayload(),
                List.of())));
        SemanticDtos.ConceptCandidateResponse candidate = new SemanticDtos.ConceptCandidateResponse(identity, "orders.find",
                List.of("find"), "SYNTAX_RESOLVED", Optional.of(new SemanticDtos.MapperStatementConceptCandidateDetailsResponse(
                "MAPPER_STATEMENT", mapping)), List.of(), List.of());

        assertThatThrownBy(() -> mapper.resolveConcept(REPOSITORY_ID, REPOSITORY_REVISION,
                new SemanticDtos.ResolveConceptResponse("orders", REVISION, candidate)))
                .isInstanceOf(CapabilityExecutionContractException.class);
    }

    @Test
    void projectsTruncatedSourceSegmentAsAProviderBoundObservation() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.SourceRangePayload location = sourceRange();
        SemanticDtos.SourceSegmentPayload segment = new SemanticDtos.SourceSegmentPayload(location, "class Orders {}",
                Optional.empty());

        CapabilityExecutionResult.Succeeded result = (CapabilityExecutionResult.Succeeded) mapper.getSourceSegment(
                REPOSITORY_ID, REPOSITORY_REVISION, new SemanticDtos.SourceSegmentResponse("orders", REVISION, segment,
                true, List.of()));

        assertThat(result.observations()).extracting(CapabilityObservation::code)
                .containsExactly(ObservationCode.TRUNCATED_CANDIDATES);
        assertThat(result.observations().getFirst().description()).doesNotContain("class Orders");
    }

    @Test
    void projectsListenerObservationSamplesAsOrderedSourceRangeCandidates() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.SourceRangePayload first = sourceRange();
        SemanticDtos.SourceRangePayload second = new SemanticDtos.SourceRangePayload("src/Other.java",
                new SemanticDtos.TextRangePayload(new SemanticDtos.Position(2, 3), new SemanticDtos.Position(2, 4)));
        SemanticDtos.PageResponse page = new SemanticDtos.PageResponse(0, 10, 0, 0, false);

        CapabilityExecutionResult.Succeeded result = (CapabilityExecutionResult.Succeeded) mapper.discoverEventListeners(
                REPOSITORY_ID, REPOSITORY_REVISION, new SemanticDtos.DiscoverEventListenersResponse("orders", REVISION,
                "com.example.Event", List.of(), page, List.of(new SemanticDtos.ListenerObservationSummaryResponse(
                "LISTENER_TARGET_UNRESOLVED", 2, List.of(first, second))), List.of()));

        assertThat(result.discoveredCandidates()).extracting(candidate -> ((SemanticTargetCandidate) candidate).semanticTarget().key())
                .containsExactly("src/ResponseService.java", "src/Other.java");
        assertThat(result.observations().getFirst().candidates()).isEqualTo(result.discoveredCandidates());
    }

    @Test
    void preservesNonMethodSourceSymbolDeclarationAndOccurrenceRangesInOrder() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.MethodTargetPayload target = methodTargetPayload();
        SemanticDtos.TextRangePayload declaration = new SemanticDtos.TextRangePayload(new SemanticDtos.Position(1, 0),
                new SemanticDtos.Position(1, 2));
        SemanticDtos.TextRangePayload occurrence = new SemanticDtos.TextRangePayload(new SemanticDtos.Position(4, 1),
                new SemanticDtos.Position(4, 3));
        SemanticDtos.VariableLikeSourceSymbolCandidateResponse candidate =
                new SemanticDtos.VariableLikeSourceSymbolCandidateResponse("FIELD",
                new SemanticDtos.SourceMemberIdentityPayload.TypeMember("TYPE", target.sourceType(), "id"),
                new SemanticDtos.DeclaredTypeResponse("long", Optional.empty()), declaration, occurrence, 2, List.of());

        CapabilityExecutionResult.Succeeded result = (CapabilityExecutionResult.Succeeded) mapper.resolveSourceSymbol(
                REPOSITORY_ID, REPOSITORY_REVISION, new SemanticDtos.ResolveSourceSymbolResponse("orders", REVISION,
                "RESOLVED", List.of(), new SemanticDtos.BoundedResultResponse(10, 1, 1, false), List.of(candidate), List.of()));

        assertThat(result.discoveredCandidates()).extracting(candidateValue -> ((SemanticTargetCandidate) candidateValue)
                .semanticTarget().sourceRange().orElseThrow().startLine()).containsExactly(2, 5);
        assertThat(result.discoveredCandidates()).extracting(AnalysisCandidate::description)
                .containsExactly("source symbol FIELD declaration", "source symbol FIELD occurrence");
    }

    @Test
    void rejectsUnknownTypeMemberEnums() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.MethodTargetPayload target = methodTargetPayload();
        SemanticDtos.PageResponse page = new SemanticDtos.PageResponse(0, 10, 0, 0, false);
        SemanticDtos.ConceptCoverageResponse coverage = new SemanticDtos.ConceptCoverageResponse("COMPLETE", 1, 1, 0);
        SemanticDtos.DiscoverTypeMembersResponse unknownTypeKind = new SemanticDtos.DiscoverTypeMembersResponse("orders",
                REVISION, target.sourceType(), "OTHER", List.of(), List.of(), List.of(), List.of(), page, coverage, List.of());
        SemanticDtos.DiscoverTypeMembersResponse unknownLimitation = new SemanticDtos.DiscoverTypeMembersResponse("orders",
                REVISION, target.sourceType(), "CLASS", List.of(), List.of(), List.of(), List.of(
                new SemanticDtos.FieldTypeMemberResponse("FIELD", new SemanticDtos.SourceMemberIdentityPayload.TypeMember(
                        "TYPE", target.sourceType(), "id"), "long", Optional.empty(), List.of(), List.of("OTHER"), List.of())),
                page, coverage, List.of());

        assertThatThrownBy(() -> mapper.discoverTypeMembers(REPOSITORY_ID, REPOSITORY_REVISION, unknownTypeKind))
                .isInstanceOf(CapabilityExecutionContractException.class);
        assertThatThrownBy(() -> mapper.discoverTypeMembers(REPOSITORY_ID, REPOSITORY_REVISION, unknownLimitation))
                .isInstanceOf(CapabilityExecutionContractException.class);
    }

    @Test
    void rejects_value_type_members_with_invalid_owner_types_or_ranges() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.SourceTypeIdentityPayload sourceType = sourceType("Order");
        SemanticDtos.SourceTypeIdentityPayload otherType = sourceType("OtherOrder");
        SemanticDtos.PageResponse page = new SemanticDtos.PageResponse(0, 10, 1, 1, false);
        SemanticDtos.ConceptCoverageResponse coverage = new SemanticDtos.ConceptCoverageResponse("COMPLETE", 1, 1, 0);
        SemanticDtos.DiscoverTypeMembersResponse mismatchedOwner = new SemanticDtos.DiscoverTypeMembersResponse("orders",
                REVISION, sourceType, "RECORD", List.of(), List.of(), List.of(), List.of(
                new SemanticDtos.EnumConstantTypeMemberResponse("ENUM_CONSTANT",
                        new SemanticDtos.SourceMemberIdentityPayload.TypeMember("TYPE", otherType, "CARD"),
                        textRange(1, 0, 1, 4), List.of(), List.of())), page, coverage, List.of());
        SemanticDtos.DiscoverTypeMembersResponse blankType = new SemanticDtos.DiscoverTypeMembersResponse("orders", REVISION,
                sourceType, "RECORD", List.of(), List.of(), List.of(), List.of(
                new SemanticDtos.RecordComponentTypeMemberResponse("RECORD_COMPONENT",
                        new SemanticDtos.SourceMemberIdentityPayload.TypeMember("TYPE", sourceType, "reference"), " ",
                        Optional.empty(), textRange(1, 0, 1, 9), List.of(), List.of())), page, coverage, List.of());
        assertThatThrownBy(() -> mapper.discoverTypeMembers(REPOSITORY_ID, REPOSITORY_REVISION, mismatchedOwner))
                .isInstanceOf(CapabilityExecutionContractException.class);
        assertThatThrownBy(() -> mapper.discoverTypeMembers(REPOSITORY_ID, REPOSITORY_REVISION, blankType))
                .isInstanceOf(CapabilityExecutionContractException.class);
        assertThatThrownBy(() -> textRange(2, 4, 2, 3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownDiscoveryStatusAndMalformedProviderJson() throws Exception {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();

        assertThatThrownBy(() -> mapper.resolveSourceSymbol(REPOSITORY_ID, REPOSITORY_REVISION,
                new SemanticDtos.ResolveSourceSymbolResponse("orders", REVISION,
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
                        "AMBIGUOUS", null, List.of(first, second), "multiple bindings", List.of()), List.of("TEMPLATE_MATCH"))),
                List.of(new SemanticDtos.ApiRouteObservationResponse("TRUNCATED_CANDIDATES", "more\nresults")));

        CapabilityExecutionResult.Succeeded result = (CapabilityExecutionResult.Succeeded) mapper.apiRoutes(
                REPOSITORY_ID, REPOSITORY_REVISION, response);

        assertThat(result.discoveredCandidates()).hasSize(3);
        assertThat(result.discoveredCandidates().get(0)).isInstanceOf(RouteCandidate.class);
        assertThat(result.discoveredCandidates().subList(1, 3)).allMatch(SemanticTargetCandidate.class::isInstance);
        assertThat(result.observations()).extracting(observation -> observation.code())
                .containsExactly(ObservationCode.AMBIGUOUS_SEMANTIC_TARGET, ObservationCode.TRUNCATED_CANDIDATES);
        assertThat(result.observations().get(1).description()).isEqualTo("more results");
    }

    @Test
    void acceptsObservationOnlyApiRouteResponsesWithoutInventingCandidateScope() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.ApiRouteCandidatesResponse response = new SemanticDtos.ApiRouteCandidatesResponse(List.of(),
                List.of(new SemanticDtos.ApiRouteObservationResponse("TRUNCATED_CANDIDATES", "more results")));

        CapabilityExecutionResult.Succeeded result = (CapabilityExecutionResult.Succeeded) mapper.apiRoutes(
                REPOSITORY_ID, REPOSITORY_REVISION, response);

        assertThat(result.discoveredCandidates()).isEmpty();
        assertThat(result.observations()).extracting(CapabilityObservation::code)
                .containsExactly(ObservationCode.TRUNCATED_CANDIDATES);
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
                REPOSITORY_ID, REPOSITORY_REVISION, methodTargetPayload(target), response);

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
                "service\ntimeout", "orders", REVISION, null, null, List.of(), List.of(), List.of(), "request-1");
        SemanticDtos.ApiErrorResponse protocol = new SemanticDtos.ApiErrorResponse("SEMANTIC_PROTOCOL_ERROR",
                "bad schema", "orders", REVISION, null, null, List.of(), List.of(), List.of(), "request-2");

        CapabilityExecutionResult.Failed result = (CapabilityExecutionResult.Failed) mapper.capability(timeout,
                "java-semantic-service:POST /v1/api-routes/lookup");

        assertThat(result.failure().code()).isEqualTo(CapabilityExecutionFailureCode.TIMEOUT);
        assertThat(result.failure().description()).isEqualTo("service timeout");
        assertThatThrownBy(() -> mapper.capability(protocol, "operation"))
                .isInstanceOf(CapabilityExecutionContractException.class);
    }

    @Test
    void mapsUnsupportedImplementationTargetToRecoverableCapabilityFailure() {
        JavaSemanticErrorMapper mapper = new JavaSemanticErrorMapper(new JavaSemanticResultMapper());
        SemanticDtos.ApiErrorResponse unsupported = new SemanticDtos.ApiErrorResponse(
                "IMPLEMENTATION_TARGET_UNSUPPORTED",
                "requested method does not support implementation discovery",
                "orders",
                REVISION,
                null,
                methodTarget("DefaultOrderWorkflow", "processOrder"),
                List.of(),
                List.of(),
                List.of(),
                "request-unsupported");

        CapabilityExecutionResult.Failed result = (CapabilityExecutionResult.Failed) mapper.capability(
                unsupported,
                "java-semantic-service:POST /v1/discovery/method-implementations");

        assertThat(result.failure().code()).isEqualTo(CapabilityExecutionFailureCode.CAPABILITY_UNAVAILABLE);
        assertThat(result.failure().description())
                .isEqualTo("requested method does not support implementation discovery");
    }

    @Test
    void rejectsImpossibleSuccessfulDtoInsteadOfPublishingTrustedOutput() {
        JavaSemanticResultMapper mapper = new JavaSemanticResultMapper();
        SemanticDtos.ApiRouteCandidatesResponse response = new SemanticDtos.ApiRouteCandidatesResponse(null, List.of());

        assertThatThrownBy(() -> mapper.apiRoutes(REPOSITORY_ID, REPOSITORY_REVISION, response))
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
                        List.of(), "resolved", List.of()), List.of("TEMPLATE_MATCH"))), List.of());
        JavaSemanticErrorMapper errorMapper = new JavaSemanticErrorMapper(mapper);
        SemanticDtos.ApiErrorResponse malformedError = new SemanticDtos.ApiErrorResponse("SEMANTIC_REQUEST_TIMEOUT",
                "timeout", "orders", REVISION, null, null, null, List.of(), List.of(), "request");

        assertThatThrownBy(() -> mapper.apiRoutes(REPOSITORY_ID, REPOSITORY_REVISION, response))
                .isInstanceOf(CapabilityExecutionContractException.class);
        assertThatThrownBy(() -> errorMapper.capability(malformedError, "operation"))
                .isInstanceOf(CapabilityExecutionContractException.class);
    }

    @Test
    void rejectsAGraphResponseWhoseRootDoesNotMatchTheRequestedTarget() {
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
        assertThatThrownBy(() -> mapper.outgoingCallGraph(
                REPOSITORY_ID, REPOSITORY_REVISION, methodTargetPayload(requested), response))
                .isInstanceOf(CapabilityExecutionContractException.class)
                .hasMessageContaining("root target");
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
                "x".repeat(501), "orders", REVISION, null, null, List.of(), List.of(), List.of(), "request");

        CapabilityExecutionResult.Failed result = (CapabilityExecutionResult.Failed) mapper.capability(timeout, "operation");

        assertThat(result.failure().code()).isEqualTo(CapabilityExecutionFailureCode.TIMEOUT);
        assertThat(result.failure().description()).hasSize(500);
    }

    private static SemanticDtos.SourceRangePayload sourceRange() {
        SemanticDtos.TextRangePayload range = new SemanticDtos.TextRangePayload(
                new SemanticDtos.Position(0, 0), new SemanticDtos.Position(0, 1));
        return new SemanticDtos.SourceRangePayload("src/ResponseService.java", range);
    }

    private static SemanticDtos.AvailableFollowUp internalReferencesFollowUp(
            SemanticDtos.SourceMemberIdentityPayload.TypeMember identity) {
        return new SemanticDtos.AvailableFollowUp("FIND_INTERNAL_REFERENCES",
                new SemanticDtos.FollowUpApi("POST", "/v1/discovery/internal-references", "findInternalReferences"),
                new SemanticDtos.TargetFollowUpRequest("orders", REVISION,
                        new SemanticDtos.InternalReferenceFollowUpTarget("MEMBER", identity), Optional.empty(),
                        Optional.of(0), Optional.of(20)));
    }

    private static SemanticDtos.GraphEdge graphEdge(String calleeNodeId, int line,
                                                     SemanticDtos.AvailableFollowUp followUp) {
        return new SemanticDtos.GraphEdge("root", calleeNodeId,
                new SemanticDtos.SourceRangePayload("src/Orders.java", textRange(line, 0, line, 1)),
                "delegate()", "JDT_CALL_HIERARCHY", "RESOLVED_ANALYZABLE", List.of(), List.of(followUp));
    }

    private static SemanticDtos.ApiEntryPointMethodResponse entryPointMethod(
            SemanticDtos.SourceTypeIdentityPayload sourceType,
            String methodName,
            String apiUrl,
            SemanticDtos.AvailableFollowUp followUp) {
        SemanticDtos.MethodTarget target = new SemanticDtos.MethodTarget(sourceType.sourceFile(),
                sourceType.javaType().packageName(), sourceType.javaType().className(), methodName, List.of());
        return new SemanticDtos.ApiEntryPointMethodResponse(methodName, methodName + " order", "API", apiUrl,
                List.of("GET"), List.of(methodName + " order"),
                new SemanticDtos.MethodTargetResolutionResponse("RESOLVED", target, List.of(), "RESOLVED_TARGET",
                        List.of(followUp)));
    }

    private static SemanticDtos.MethodTargetPayload methodTargetPayload() {
        SemanticDtos.JavaTypeIdentityPayload javaType = new SemanticDtos.JavaTypeIdentityPayload("com.example",
                "Orders");
        SemanticDtos.SourceTypeIdentityPayload sourceType = new SemanticDtos.SourceTypeIdentityPayload(javaType,
                "src/Orders.java");
        return new SemanticDtos.MethodTargetPayload(sourceType, "find", List.of());
    }

    private static SemanticDtos.MethodTargetPayload methodTargetPayload(SemanticDtos.MethodTarget target) {
        SemanticDtos.JavaTypeIdentityPayload javaType = new SemanticDtos.JavaTypeIdentityPayload(target.packageName(),
                target.className());
        SemanticDtos.SourceTypeIdentityPayload sourceType = new SemanticDtos.SourceTypeIdentityPayload(javaType,
                target.sourceFile());
        return new SemanticDtos.MethodTargetPayload(sourceType, target.methodName(), target.parameterTypes());
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

    private static SemanticDtos.SourceTypeIdentityPayload sourceType(String className) {
        return new SemanticDtos.SourceTypeIdentityPayload(
                new SemanticDtos.JavaTypeIdentityPayload("com.example", className), "src/" + className + ".java");
    }

    private static SemanticDtos.MethodTargetResolutionResponse resolved(SemanticDtos.MethodTarget target) {
        return new SemanticDtos.MethodTargetResolutionResponse("RESOLVED", target, List.of(), "RESOLVED_TARGET", List.of());
    }

    private static SemanticDtos.TextRangePayload textRange(int startLine, int startCharacter, int endLine,
                                                            int endCharacter) {
        return new SemanticDtos.TextRangePayload(new SemanticDtos.Position(startLine, startCharacter),
                new SemanticDtos.Position(endLine, endCharacter));
    }

    private static SemanticTarget sourceRangeTarget(String sourceFile, SemanticDtos.TextRangePayload range) {
        return new SemanticTarget(SemanticTargetKind.SOURCE_RANGE, sourceFile, Optional.of(new SourceRange(sourceFile,
                range.start().line() + 1, range.start().character() + 1, range.end().line() + 1,
                range.end().character() + 1)));
    }

    private static void assertMetadataEvidence(List<EvidenceRef> evidence, int expectedCount,
                                               RepositoryRevision revision) {
        assertThat(evidence).hasSize(expectedCount);
        for (EvidenceRef reference : evidence) {
            assertThat(reference.sourceService()).isEqualTo("java-semantic-service");
            assertThat(reference.repositoryId()).isEqualTo(REPOSITORY_ID);
            assertThat(reference.repositoryRevision()).isEqualTo(revision);
            assertThat(reference.content()).isNotBlank().doesNotContain("\n", "\r");
            assertThat(reference.warnings()).isEmpty();
            assertThat(reference.artifactRef()).isEqualTo(JavaSemanticArtifactDigest.fromContent(reference.content()));
        }
    }
}
