package com.java.system.agent.codeintelligence.semantic;

import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import com.java.system.agent.answering.port.out.CapabilityExecutionContractException;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 驗證 Java Semantic Service v1 回應在進入 answering 前符合 OpenAPI 文件
 */
final class JavaSemanticProviderSchemaValidator {

    private static final Pattern REPOSITORY_ID = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");
    private static final Pattern REVISION = Pattern.compile("(?:[0-9a-f]{40}|FIXTURE)");
    private static final Set<String> REPOSITORY_MODES = Set.of("REMOTE", "LOCAL_FIXTURE");
    private static final Set<String> RESOLUTION_STATUSES = Set.of("RESOLVED", "UNRESOLVED", "AMBIGUOUS");
    private static final Set<String> GRAPH_STATUSES = Set.of("SUCCESS", "PARTIAL");
    private static final Set<String> CONTENT_STATES = Set.of("FULL_SOURCE", "TARGET_ONLY", "EXTERNAL");
    private static final Set<String> TRAVERSAL_STATES = Set.of("EXPANDED", "DEPTH_BOUNDARY", "BUDGET_CUTOFF", "OPAQUE", "EXTERNAL");
    private static final Set<String> DISPATCH_KINDS = Set.of("SYNCHRONOUS", "ASYNC");
    private static final Set<String> LIMIT_REASONS = Set.of("NONE", "NODE_BUDGET");
    private static final Set<String> EDGE_STRATEGIES = Set.of("JDT_CALL_HIERARCHY", "JDT_DEFINITION_FALLBACK",
            "SPRING_BEAN_BY_QUALIFIER", "SPRING_BEAN_BY_PRIMARY", "SPRING_SINGLE_IMPLEMENTATION", "MYBATIS_MAPPER",
            "SPRING_DATA_REPOSITORY", "LOMBOK_GENERATED", "EXTERNAL_LIBRARY", "FEIGN_CLIENT",
            "BUSINESS_READ_FORBIDDEN", "SPRING_MULTIPLE_CANDIDATES", "DATA_ACCESS_WITHOUT_EVIDENCE");
    private static final Set<String> EDGE_CATEGORIES = Set.of("RESOLVED_ANALYZABLE", "RESOLVED_OPAQUE", "UNRESOLVED_GUESS");
    private static final Set<String> WARNING_CODES = Set.of("DESCENDANT_CALL_AMBIGUOUS", "DESCENDANT_CALL_UNRESOLVED",
            "INCOMING_CALLER_REJECTED", "NODE_BUDGET_REACHED");
    private static final Set<String> ERROR_CODES = Set.of("CHILD_SEMANTIC_QUERY_FAILED");
    private static final Set<String> API_MATCH_REASONS = Set.of("EXACT_NORMALIZED_PATH", "TEMPLATE_MATCH", "HTTP_METHOD_MATCH",
            "SHARED_STATIC_SEGMENT", "POSITIONAL_STATIC_SEGMENT", "SAME_SEGMENT_COUNT");
    private static final Set<String> API_ERROR_CODES = Set.of("REQUEST_INVALID", "SEMANTIC_UNAUTHORIZED",
            "REPOSITORY_NOT_FOUND", "REPOSITORY_NOT_READY", "REPOSITORY_REVISION_MISMATCH", "SEMANTIC_BINDING_AMBIGUOUS",
            "SEMANTIC_TARGET_NOT_FOUND", "SEMANTIC_BINDING_UNRESOLVED", "SEMANTIC_PROTOCOL_ERROR",
            "SEMANTIC_ENGINE_START_FAILED", "SEMANTIC_REQUEST_TIMEOUT", "IMPLEMENTATION_TARGET_UNSUPPORTED",
            "CONCEPT_KIND_UNAVAILABLE", "INTERNAL_ERROR", "SEMANTIC_AUTH_DISABLED");
    private static final Set<String> CONCEPT_KINDS = Set.of("TYPE", "METHOD", "FIELD", "ANNOTATION_USAGE",
            "TYPE_USAGE", "API_ROUTE", "MQ_DESTINATION", "SCHEDULE", "MAPPER_STATEMENT", "SQL_IDENTIFIER",
            "CONFIGURATION_KEY", "OUTBOUND_API", "MQ_PUBLISHER", "ERROR_CONTRACT", "ENUM_CONSTANT");

    List<SemanticDtos.RepositoryStatusResponse> repositories(List<SemanticDtos.RepositoryStatusResponse> responses) {
        List<SemanticDtos.RepositoryStatusResponse> validated = requiredList(responses, "repository response");
        for (SemanticDtos.RepositoryStatusResponse response : validated) {
            repository(response);
        }
        return validated;
    }

    SemanticDtos.RepositoryStatusResponse repository(SemanticDtos.RepositoryStatusResponse response) {
        SemanticDtos.RepositoryStatusResponse required = requiredObject(response, "repository status response");
        repositoryId(required.repoId(), "repository ID");
        enumValue(required.mode(), REPOSITORY_MODES, "repository mode");
        requiredString(required.displayName(), "repository display name");
        requiredBoolean(required.cloned(), "repository cloned");
        optionalRevision(required.currentRevision(), "repository current revision");
        return required;
    }

    SemanticDtos.EntryPointsResponse entryPoints(SemanticDtos.EntryPointsResponse response) {
        SemanticDtos.EntryPointsResponse required = requiredObject(response, "entry-points response");
        repositoryId(required.repoId(), "entry-points repository ID");
        revision(required.analyzedRevision(), "entry-points analyzed revision");
        for (SemanticDtos.EntryPointClassResponse entryPoint : requiredList(required.entryPoints(), "entry point")) {
            sourceTypePayload(entryPoint.sourceType());
            requiredString(entryPoint.description(), "entry point description");
            requiredStrings(entryPoint.basePaths(), "entry point base path");
            for (SemanticDtos.EntryPointMethodResponse method : requiredList(entryPoint.methods(), "entry point method")) {
                entryPointMethod(method);
            }
        }
        return required;
    }

    SemanticDtos.ApiRouteCandidatesResponse apiRoutes(SemanticDtos.ApiRouteCandidatesResponse response) {
        SemanticDtos.ApiRouteCandidatesResponse required = requiredObject(response, "API route candidates response");
        for (SemanticDtos.ApiRouteCandidateResponse candidate : requiredList(required.candidates(), "API route candidate")) {
            repositoryId(candidate.repoId(), "route repository ID");
            revision(candidate.analyzedRevision(), "route analyzed revision");
            requiredString(candidate.httpMethod(), "route HTTP method");
            requiredString(candidate.routeTemplate(), "route template");
            requiredString(candidate.packageName(), "route package name");
            requiredString(candidate.className(), "route class name");
            requiredString(candidate.methodName(), "route method name");
            resolution(candidate.analysisTarget());
            for (String reason : requiredStrings(candidate.matchReasons(), "route match reason")) {
                enumValue(reason, API_MATCH_REASONS, "route match reason");
            }
        }
        for (SemanticDtos.ApiRouteObservationResponse observation : requiredList(required.observations(), "API route observation")) {
            enumValue(observation.code(), Set.of("TRUNCATED_CANDIDATES"), "API route observation code");
            requiredString(observation.description(), "API route observation description");
        }
        return required;
    }

    void graph(String status, String analyzedRevision, String rootNodeId, SemanticDtos.GraphTraversal traversal,
               List<SemanticDtos.GraphNode> nodes, List<SemanticDtos.GraphEdge> edges,
               List<SemanticDtos.GraphWarning> warnings, List<SemanticDtos.GraphError> errors) {
        enumValue(status, GRAPH_STATUSES, "graph status");
        revision(analyzedRevision, "graph analyzed revision");
        requiredString(rootNodeId, "graph root node ID");
        graphTraversal(traversal);
        for (SemanticDtos.GraphNode node : requiredList(nodes, "graph node")) {
            graphNode(node);
        }
        for (SemanticDtos.GraphEdge edge : requiredList(edges, "graph edge")) {
            graphEdge(edge);
        }
        for (SemanticDtos.GraphWarning warning : requiredList(warnings, "graph warning")) {
            graphWarning(warning);
        }
        for (SemanticDtos.GraphError error : requiredList(errors, "graph error")) {
            graphError(error);
        }
    }

    void error(SemanticDtos.ApiErrorResponse response) {
        SemanticDtos.ApiErrorResponse required = requiredObject(response, "API error response");
        enumValue(required.errorCode(), API_ERROR_CODES, "API error code");
        requiredString(required.message(), "API error message");
        optionalRepositoryId(required.repoId(), "API error repository ID");
        optionalRevision(required.expectedRevision(), "API error expected revision");
        optionalRevision(required.currentRevision(), "API error current revision");
        if (Objects.nonNull(required.target())) {
            methodTarget(required.target());
        }
        if ("CONCEPT_KIND_UNAVAILABLE".equals(required.errorCode())) {
            conceptKinds(required.unavailableKinds());
            conceptKinds(required.supportedKinds());
        } else {
            requiredMethodTargets(required.candidates(), "API error candidate");
        }
    }

    SemanticDtos.DiscoverConceptsResponse discoverConcepts(SemanticDtos.DiscoverConceptsResponse response) {
        SemanticDtos.DiscoverConceptsResponse required = requiredObject(response, "concept discovery response");
        discoveryScope(required.repoId(), required.analyzedRevision(), "concept discovery");
        pageResponse(required.page(), "concept discovery page");
        coverage(required.coverage(), "concept discovery coverage");
        enumValues(required.searchedKinds(), CONCEPT_KINDS, "searched concept kind");
        enumValues(required.supportedKinds(), CONCEPT_KINDS, "supported concept kind");
        enumValues(required.limitations(), Set.of("SOURCE_BODY_NOT_SEARCHED"), "concept limitation");
        for (SemanticDtos.ConceptCandidateResponse candidate : requiredList(required.candidates(), "concept candidate")) {
            conceptIdentity(candidate.identity());
            enumValue(candidate.authority(), Set.of("SYNTAX_RESOLVED", "SYNTAX_DECLARED", "FRAMEWORK_METADATA",
                    "WRITTEN_NAME_FALLBACK", "METADATA_VALUE_UNRESOLVED"), "concept authority");
            candidate.details().ifPresent(details -> conceptCandidateDetails(candidate.identity(), details));
            for (SemanticDtos.ConceptEvidenceResponse evidence : requiredList(candidate.evidence(), "concept evidence")) {
                conceptIdentity(evidence.identity());
            }
            followUps(candidate.availableFollowUps());
        }
        issueSummaries(required.issueSummaries(), Set.of("MQ_DESTINATION_UNRESOLVED", "SCHEDULE_TRIGGER_VALUE_UNRESOLVED"),
                "concept issue");
        followUps(required.availableFollowUps());
        return required;
    }

    SemanticDtos.ResolveConceptResponse resolveConcept(SemanticDtos.ResolveConceptResponse response) {
        SemanticDtos.ResolveConceptResponse required = requiredObject(response, "resolve concept response");
        discoveryScope(required.repoId(), required.analyzedRevision(), "resolve concept");
        SemanticDtos.ConceptCandidateResponse candidate = requiredObject(required.candidate(), "resolved concept candidate");
        conceptIdentity(candidate.identity());
        candidate.details().ifPresent(details -> conceptCandidateDetails(candidate.identity(), details));
        for (SemanticDtos.ConceptEvidenceResponse evidence : requiredList(candidate.evidence(), "resolved concept evidence")) {
            conceptIdentity(evidence.identity());
        }
        followUps(candidate.availableFollowUps());
        return required;
    }

    private void conceptCandidateDetails(SemanticDtos.ConceptFollowUpIdentity identity,
                                         SemanticDtos.ConceptCandidateDetailsResponse details) {
        if (details instanceof SemanticDtos.FieldConceptCandidateDetailsResponse field) {
            if (!"FIELD".equals(identity.kind())) {
                throw contract("field concept details do not match the concept identity");
            }
            enumValue(field.kind(), Set.of("FIELD"), "field concept details kind");
            fieldTypeReference(field.declaredType());
        } else if (details instanceof SemanticDtos.MapperStatementConceptCandidateDetailsResponse mapper) {
            if (!"MAPPER_STATEMENT".equals(identity.kind())) {
                throw contract("mapper statement details do not match the concept identity");
            }
            enumValue(mapper.kind(), Set.of("MAPPER_STATEMENT"), "mapper statement details kind");
            SemanticDtos.MapperStatementKeyPayload identityKey = requiredConceptIdentity(identity,
                    SemanticDtos.MapperStatementKeyPayload.class);
            if (!identityKey.equals(mapper.mapping().statement())) {
                throw contract("mapper statement details do not match the concept identity key");
            }
            mapperStatementMapping(mapper.mapping());
        } else {
            throw contract("unsupported concept candidate details");
        }
    }

    private void mapperStatementMapping(SemanticDtos.MapperStatementMappingResponse mapping) {
        if (mapping instanceof SemanticDtos.ResolvedMapperStatementMappingResponse resolved) {
            enumValue(resolved.status(), Set.of("RESOLVED"), "resolved mapper mapping status");
            if (resolved.candidates().size() != 1) {
                throw contract("resolved mapper mapping must have one candidate");
            }
        } else if (mapping instanceof SemanticDtos.AmbiguousMapperStatementMappingResponse ambiguous) {
            enumValue(ambiguous.status(), Set.of("AMBIGUOUS"), "ambiguous mapper mapping status");
            if (ambiguous.candidates().size() < 2) {
                throw contract("ambiguous mapper mapping must have at least two candidates");
            }
        } else if (mapping instanceof SemanticDtos.UnresolvedMapperStatementMappingResponse unresolved) {
            enumValue(unresolved.status(), Set.of("UNRESOLVED"), "unresolved mapper mapping status");
            enumValue(unresolved.reason(), Set.of("INCOMPLETE_METHOD_RESOLUTION"), "unresolved mapper mapping reason");
        } else {
            throw contract("unsupported mapper statement mapping");
        }
        mapperStatementKey(mapping.statement());
        for (SemanticDtos.MapperSourceMethodCandidateResponse candidate : requiredList(mapping.candidates(),
                "mapper source method candidate")) {
            methodTargetPayload(candidate.target());
            followUps(candidate.availableFollowUps());
        }
    }

    private void fieldTypeReference(SemanticDtos.FieldTypeReferenceResponse reference) {
        if (reference instanceof SemanticDtos.NamedFieldTypeReferenceResponse named) {
            enumValue(named.kind(), Set.of("NAMED"), "named field type kind");
            nonblank(named.simpleTypeName(), "named field simple type name");
            named.resolvedJavaType().ifPresent(this::javaTypePayload);
        } else if (reference instanceof SemanticDtos.ParameterizedFieldTypeReferenceResponse parameterized) {
            enumValue(parameterized.kind(), Set.of("PARAMETERIZED"), "parameterized field type kind");
            fieldTypeReference(parameterized.rawType());
            for (SemanticDtos.FieldTypeReferenceResponse argument : requiredList(parameterized.typeArguments(),
                    "parameterized field type argument")) {
                fieldTypeReference(argument);
            }
        } else if (reference instanceof SemanticDtos.PrimitiveFieldTypeReferenceResponse primitive) {
            enumValue(primitive.kind(), Set.of("PRIMITIVE"), "primitive field type kind");
        } else if (reference instanceof SemanticDtos.ArrayFieldTypeReferenceResponse array) {
            enumValue(array.kind(), Set.of("ARRAY"), "array field type kind");
            if (array.dimensions() < 1) {
                throw contract("array field type dimensions are outside their supported range");
            }
            fieldTypeReference(array.elementType());
        } else if (reference instanceof SemanticDtos.WildcardFieldTypeReferenceResponse wildcard) {
            enumValue(wildcard.kind(), Set.of("WILDCARD"), "wildcard field type kind");
            wildcard.upperBound().ifPresent(this::fieldTypeReference);
            wildcard.lowerBound().ifPresent(this::fieldTypeReference);
        } else if (reference instanceof SemanticDtos.TypeVariableFieldTypeReferenceResponse variable) {
            enumValue(variable.kind(), Set.of("TYPE_VARIABLE"), "type variable field type kind");
            nonblank(variable.variableName(), "type variable name");
            for (SemanticDtos.FieldTypeReferenceResponse bound : requiredList(variable.upperBounds(),
                    "type variable field bound")) {
                fieldTypeReference(bound);
            }
        } else {
            throw contract("unsupported field type reference");
        }
        nonblank(reference.writtenType(), "field type written type");
    }

    SemanticDtos.DiscoverEventListenersResponse discoverEventListeners(SemanticDtos.DiscoverEventListenersResponse response) {
        SemanticDtos.DiscoverEventListenersResponse required = requiredObject(response, "event listener response");
        discoveryScope(required.repoId(), required.analyzedRevision(), "event listener");
        nonblank(required.requestedEventType(), "requested event type");
        pageResponse(required.page(), "event listener page");
        for (SemanticDtos.EventListenerCandidateResponse candidate : requiredList(required.candidates(), "event listener candidate")) {
            methodTargetPayload(candidate.target());
            textRange(candidate.sourceRange(), "event listener source range");
            List<SemanticDtos.ListenerAnnotationEvidenceResponse> annotations = requiredList(candidate.listenerAnnotations(),
                    "event listener annotation");
            if (annotations.isEmpty()) {
                throw contract("event listener candidate must have an annotation");
            }
            for (SemanticDtos.ListenerAnnotationEvidenceResponse annotation : annotations) {
                enumValue(annotation.kind(), Set.of("EVENT_LISTENER", "TRANSACTIONAL_EVENT_LISTENER"),
                        "event listener annotation kind");
                enumValue(annotation.matchKind(), Set.of("RESOLVED_IDENTITY", "WRITTEN_NAME"),
                        "event listener annotation match kind");
            }
            followUps(candidate.availableFollowUps());
        }
        for (SemanticDtos.ListenerObservationSummaryResponse summary : requiredList(required.observationSummaries(),
                "event listener observation")) {
            enumValue(summary.code(), Set.of("LISTENER_TARGET_UNRESOLVED"), "event listener observation code");
            if (summary.totalCount() < 1) {
                throw contract("event listener observation count is outside its supported range");
            }
            if (summary.samples().size() > 5) {
                throw contract("event listener observation samples exceed their supported range");
            }
            for (SemanticDtos.SourceRangePayload sample : summary.samples()) {
                sourceRange(sample, "event listener observation sample");
            }
        }
        followUps(required.availableFollowUps());
        return required;
    }

    SemanticDtos.DiscoverMethodImplementationsResponse discoverMethodImplementations(
            SemanticDtos.DiscoverMethodImplementationsResponse response) {
        SemanticDtos.DiscoverMethodImplementationsResponse required = requiredObject(response, "method implementation response");
        discoveryScope(required.repoId(), required.revision(), "method implementation");
        methodTargetPayload(required.requestedTarget());
        bounded(required.limits(), "method implementation limits");
        enumValue(required.resolution().status(), Set.of("COMPLETE", "PARTIAL"), "method implementation status");
        issueSummaries(required.resolution().issueSummaries(), Set.of("LOCAL_CONVERSION_FAILED", "EXTERNAL_TARGET",
                "CANONICAL_TARGET_UNRESOLVED", "NON_EXECUTABLE_TARGET"), "method implementation issue");
        for (SemanticDtos.MethodImplementationCandidateResponse candidate : requiredList(required.candidates(),
                "method implementation candidate")) {
            methodTargetPayload(candidate.target());
            followUps(candidate.availableFollowUps());
        }
        return required;
    }

    SemanticDtos.DiscoverTypeMembersResponse discoverTypeMembers(SemanticDtos.DiscoverTypeMembersResponse response) {
        SemanticDtos.DiscoverTypeMembersResponse required = requiredObject(response, "type member response");
        discoveryScope(required.repoId(), required.analyzedRevision(), "type member");
        sourceTypePayload(required.sourceType());
        enumValue(required.typeKind(), Set.of("CLASS", "INTERFACE", "RECORD", "ENUM"), "type member type kind");
        nonemptyOrEmptyStrings(required.annotations(), "type member annotation");
        nonemptyOrEmptyStrings(required.implementedTypes(), "type member implemented type");
        nonemptyOrEmptyStrings(required.extendedTypes(), "type member extended type");
        pageResponse(required.page(), "type member page");
        coverage(required.coverage(), "type member coverage");
        for (SemanticDtos.TypeMemberResponse member : requiredList(required.members(), "type member")) {
            if (member instanceof SemanticDtos.MethodTypeMemberResponse method) {
                enumValue(method.kind(), Set.of("METHOD"), "type member kind");
                methodTargetPayload(method.target());
            } else if (member instanceof SemanticDtos.FieldTypeMemberResponse field) {
                enumValue(field.kind(), Set.of("FIELD"), "type member kind");
                sourceMemberIdentity(field.identity());
                nonblank(field.writtenType(), "type member field written type");
                field.resolvedType().ifPresent(value -> nonblank(value, "type member field resolved type"));
                nonemptyOrEmptyStrings(field.annotations(), "type member field annotation");
                enumValues(field.limitations(), Set.of("FIELD_USAGE_NOT_INDEXED"), "type member field limitation");
            } else if (member instanceof SemanticDtos.EnumConstantTypeMemberResponse enumConstant) {
                enumValue(enumConstant.kind(), Set.of("ENUM_CONSTANT"), "type member kind");
                typeMemberOwnedBy(enumConstant.identity(), required.sourceType(), "enum constant");
                textRange(enumConstant.declarationRange(), "type member enum constant declaration range");
                nonemptyOrEmptyStrings(enumConstant.annotations(), "type member enum constant annotation");
            } else if (member instanceof SemanticDtos.RecordComponentTypeMemberResponse recordComponent) {
                enumValue(recordComponent.kind(), Set.of("RECORD_COMPONENT"), "type member kind");
                typeMemberOwnedBy(recordComponent.identity(), required.sourceType(), "record component");
                nonblank(recordComponent.writtenType(), "type member record component written type");
                recordComponent.resolvedType().ifPresent(value -> nonblank(value,
                        "type member record component resolved type"));
                textRange(recordComponent.declarationRange(), "type member record component declaration range");
                nonemptyOrEmptyStrings(recordComponent.annotations(), "type member record component annotation");
            } else {
                throw contract("unsupported type member response");
            }
            followUps(member.availableFollowUps());
        }
        followUps(required.availableFollowUps());
        return required;
    }

    SemanticDtos.FindInternalReferencesResponse findInternalReferences(SemanticDtos.FindInternalReferencesResponse response) {
        SemanticDtos.FindInternalReferencesResponse required = requiredObject(response, "internal reference response");
        discoveryScope(required.repoId(), required.analyzedRevision(), "internal reference");
        enumValue(required.status(), Set.of("COMPLETE", "PARTIAL"), "internal reference status");
        if (required.totalReferenceCount() < 0) {
            throw contract("internal reference total count is outside its supported range");
        }
        pageResponse(required.page(), "internal reference page");
        issueSummaries(required.issueSummaries(), Set.of("REFERENCE_SOURCE_NOT_JAVA", "REFERENCE_SOURCE_OUTSIDE_SNAPSHOT",
                "REFERENCE_CONTEXT_UNRESOLVED", "REFERENCE_RANGE_INVALID"), "internal reference issue");
        SemanticDtos.InternalReferenceTargetDeclarationResponse declaration = requiredObject(required.targetDeclaration(),
                "internal reference target declaration");
        internalReferenceTarget(declaration.target());
        textRange(declaration.declarationRange(), "internal reference declaration range");
        followUps(declaration.availableFollowUps());
        for (SemanticDtos.ReferenceGroupResponse group : requiredList(required.referenceGroups(), "reference group")) {
            internalReferenceContext(group.context());
            bounded(group.limits(), "reference group limits");
            if (group.representativeReferences().size() > 3) {
                throw contract("reference representative count exceeds its supported range");
            }
            followUps(group.availableFollowUps());
            unavailableFollowUps(group.unavailableFollowUps());
            for (SemanticDtos.ReferenceOccurrenceResponse occurrence : requiredList(group.representativeReferences(),
                    "reference occurrence")) {
                textRange(occurrence.range(), "reference occurrence range");
                followUps(occurrence.availableFollowUps());
            }
        }
        followUps(required.availableFollowUps());
        return required;
    }

    private void internalReferenceContext(SemanticDtos.InternalReferenceContextResponse context) {
        if (context instanceof SemanticDtos.InternalReferenceTypeContextResponse type) {
            enumValue(type.kind(), Set.of("TYPE"), "internal reference type context kind");
            sourceTypePayload(type.sourceType());
        } else if (context instanceof SemanticDtos.InternalReferenceMethodContextResponse method) {
            enumValue(method.kind(), Set.of("METHOD"), "internal reference method context kind");
            methodTargetPayload(method.method());
        } else {
            throw contract("unsupported internal reference context");
        }
    }

    private void unavailableFollowUps(List<SemanticDtos.UnavailableFollowUpResponse> unavailable) {
        for (SemanticDtos.UnavailableFollowUpResponse response : requiredList(unavailable, "unavailable follow-up")) {
            enumValue(response.reason(), Set.of("NO_MATCHING_STRUCTURED_CONCEPT", "SEARCH_INCOMPLETE"),
                    "unavailable follow-up reason");
            enumValue(response.recommendedAction(), Set.of("REFINE_TERMS_KINDS_OR_PACKAGE_FILTERS", "FIX_SOURCE_OR_RETRY"),
                    "unavailable follow-up action");
        }
    }

    SemanticDtos.EvidenceSourceResponse evidenceSource(SemanticDtos.EvidenceSourceResponse response) {
        SemanticDtos.EvidenceSourceResponse required = requiredObject(response, "evidence source response");
        discoveryScope(required.repoId(), required.analyzedRevision(), "evidence source");
        evidenceIdentity(required.identity());
        sourceRange(required.location(), "evidence source location");
        segment(required.segment(), "evidence source segment");
        followUps(required.availableFollowUps());
        return required;
    }

    SemanticDtos.MethodSourceResponse methodSource(SemanticDtos.MethodSourceResponse response) {
        SemanticDtos.MethodSourceResponse required = requiredObject(response, "method source response");
        discoveryScope(required.repoId(), required.analyzedRevision(), "method source");
        sourceRange(required.declarationLocation(), "method declaration location");
        segment(required.segment(), "method source segment");
        followUps(required.availableFollowUps());
        return required;
    }

    SemanticDtos.SourceSegmentResponse sourceSegment(SemanticDtos.SourceSegmentResponse response) {
        SemanticDtos.SourceSegmentResponse required = requiredObject(response, "source segment response");
        discoveryScope(required.repoId(), required.analyzedRevision(), "source segment");
        segment(required.segment(), "source segment");
        followUps(required.availableFollowUps());
        return required;
    }

    SemanticDtos.ResolveSourceSymbolResponse resolveSourceSymbol(SemanticDtos.ResolveSourceSymbolResponse response) {
        SemanticDtos.ResolveSourceSymbolResponse required = requiredObject(response, "source symbol response");
        discoveryScope(required.repoId(), required.analyzedRevision(), "source symbol");
        enumValue(required.status(), Set.of("RESOLVED", "CONTEXT_NOT_FOUND", "SYMBOL_NOT_FOUND", "AMBIGUOUS_CONTEXT",
                "AMBIGUOUS_SYMBOL", "AMBIGUOUS_OCCURRENCE", "UNRESOLVED_BINDING", "POSITION_MISMATCH"),
                "source symbol status");
        bounded(required.contextCandidateLimits(), "source symbol context candidate limits");
        for (SemanticDtos.SourceContextCandidateResponse context : requiredList(required.contextCandidates(),
                "source symbol context candidate")) {
            if (context instanceof SemanticDtos.SourceTypeContextCandidateResponse type) {
                enumValue(type.kind(), Set.of("SOURCE_TYPE"), "source type context kind");
                nonblank(type.sourceFile(), "source type context file");
            } else if (context instanceof SemanticDtos.SourceMethodContextCandidateResponse method) {
                enumValue(method.kind(), Set.of("METHOD"), "source method context kind");
                methodTargetPayload(method.target());
            } else {
                throw contract("unsupported source symbol context candidate");
            }
            followUp(context.retry());
        }
        sourceSymbolIssues(required.issues());
        for (SemanticDtos.SourceSymbolCandidateResponse candidate : requiredList(required.candidates(),
                "source symbol candidate")) {
            sourceSymbolCandidate(candidate);
            followUps(candidate.availableFollowUps());
        }
        return required;
    }

    private void sourceSymbolCandidate(SemanticDtos.SourceSymbolCandidateResponse candidate) {
        if (candidate instanceof SemanticDtos.VariableLikeSourceSymbolCandidateResponse variable) {
            enumValue(variable.kind(), Set.of("FIELD", "RECORD_COMPONENT", "PARAMETER", "LOCAL_VARIABLE",
                    "ENUM_CONSTANT"), "variable source symbol kind");
            sourceMemberIdentity(variable.identity());
            declaredType(variable.declaredType());
        } else if (candidate instanceof SemanticDtos.StaticConstantSourceSymbolCandidateResponse constant) {
            enumValue(constant.kind(), Set.of("STATIC_CONSTANT"), "constant source symbol kind");
            sourceMemberIdentity(constant.identity());
            declaredType(constant.declaredType());
            nonblank(constant.initializerSource(), "constant initializer source");
        } else if (candidate instanceof SemanticDtos.MethodSourceSymbolCandidateResponse method) {
            enumValue(method.kind(), Set.of("METHOD"), "method source symbol kind");
            methodTargetPayload(method.target());
        } else if (candidate instanceof SemanticDtos.SourceTypeSymbolCandidateResponse type) {
            enumValue(type.kind(), Set.of("SOURCE_TYPE"), "source type symbol kind");
            sourceTypePayload(type.identity());
        } else {
            throw contract("unsupported source symbol candidate");
        }
        textRange(candidate.declarationRange(), "source symbol declaration range");
        textRange(candidate.representativeOccurrence(), "source symbol representative occurrence");
        if (candidate.occurrenceCount() < 1) {
            throw contract("source symbol occurrence count is outside its supported range");
        }
    }

    private void declaredType(SemanticDtos.DeclaredTypeResponse declaredType) {
        SemanticDtos.DeclaredTypeResponse required = requiredObject(declaredType, "declared type");
        nonblank(required.writtenType(), "declared written type");
        required.resolvedType().ifPresent(value -> nonblank(value, "declared resolved type"));
    }

    private void sourceSymbolIssues(List<SemanticDtos.SourceSymbolIssueSummaryResponse> issues) {
        for (SemanticDtos.SourceSymbolIssueSummaryResponse issue : requiredList(issues, "source symbol issue")) {
            enumValue(issue.code(), Set.of("SOURCE_BINDING_UNRESOLVED", "UNSUPPORTED_SOURCE_CONSTRUCT"),
                    "source symbol issue code");
            if (issue.count() < 1) {
                throw contract("source symbol issue count is outside its supported range");
            }
        }
    }

    private void discoveryScope(String repositoryId, String revision, String description) {
        repositoryId(repositoryId, description + " repository ID");
        revision(revision, description + " analyzed revision");
    }

    private void pageResponse(SemanticDtos.PageResponse page, String description) {
        SemanticDtos.PageResponse required = requiredObject(page, description);
        range(required.offset(), 0, Integer.MAX_VALUE, description + " offset");
        range(required.limit(), 1, 100, description + " limit");
        if (required.returnedCount() < 0 || required.totalCount() < 0 || required.returnedCount() > required.totalCount()) {
            throw contract(description + " counts are inconsistent");
        }
        boolean expectedHasMore = (long) required.offset() + required.returnedCount() < required.totalCount();
        if (required.hasMore() != expectedHasMore) {
            throw contract(description + " hasMore is inconsistent");
        }
    }

    private void bounded(SemanticDtos.BoundedResultResponse limits, String description) {
        SemanticDtos.BoundedResultResponse required = requiredObject(limits, description);
        if (required.limit() < 1 || required.returnedCount() < 0 || required.totalCount() < 0
                || required.returnedCount() > required.totalCount()
                || required.truncated() != (required.totalCount() > required.returnedCount())) {
            throw contract(description + " counts are inconsistent");
        }
    }

    private void coverage(SemanticDtos.ConceptCoverageResponse coverage, String description) {
        SemanticDtos.ConceptCoverageResponse required = requiredObject(coverage, description);
        enumValue(required.status(), Set.of("COMPLETE", "PARTIAL"), description + " status");
        if (required.scannedFileCount() < 0 || required.extractedFileCount() < 0 || required.syntaxFailedFileCount() < 0) {
            throw contract(description + " counts are outside their supported range");
        }
    }

    private void segment(SemanticDtos.SourceSegmentPayload segment, String description) {
        SemanticDtos.SourceSegmentPayload required = requiredObject(segment, description);
        sourceRange(required.location(), description + " location");
        requiredString(required.content(), description + " content");
        required.nextLocation().ifPresent(value -> sourceRange(value, description + " continuation location"));
    }

    private void followUps(List<SemanticDtos.AvailableFollowUp> followUps) {
        for (SemanticDtos.AvailableFollowUp followUp : requiredList(followUps, "discovery follow-up")) {
            followUp(followUp);
        }
    }

    private void issueSummaries(List<SemanticDtos.IssueSummaryResponse> summaries, Set<String> codes,
                                String description) {
        for (SemanticDtos.IssueSummaryResponse summary : requiredList(summaries, description)) {
            enumValue(summary.code(), codes, description + " code");
            if (summary.count() < 1) {
                throw contract(description + " count is outside its supported range");
            }
        }
    }

    private void enumValues(List<String> values, Set<String> supported, String description) {
        for (String value : requiredList(values, description)) {
            enumValue(value, supported, description);
        }
    }

    SemanticDtos.AvailableFollowUp followUp(SemanticDtos.AvailableFollowUp followUp) {
        SemanticDtos.AvailableFollowUp required = requiredObject(followUp, "Semantic follow-up");
        requiredString(required.operation(), "Semantic follow-up operation");
        SemanticDtos.FollowUpApi api = requiredObject(required.api(), "Semantic follow-up API");
        requiredString(api.method(), "Semantic follow-up API method");
        requiredString(api.path(), "Semantic follow-up API path");
        requiredString(api.operationId(), "Semantic follow-up API operation ID");
        SemanticDtos.AvailableFollowUpRequest request = requiredObject(required.request(), "Semantic follow-up request");
        repositoryId(request.repoId(), "Semantic follow-up repository ID");
        revision(request.expectedRevision(), "Semantic follow-up expected revision");
        validateFollowUpOperation(required.operation(), request);
        return required;
    }

    private void validateFollowUpOperation(String operation, SemanticDtos.AvailableFollowUpRequest request) {
        switch (operation) {
            case "GET_METHOD_SOURCE" -> validateMethodSource(request);
            case "ANALYZE_OUTGOING_CALL_GRAPH", "ANALYZE_INCOMING_CALL_GRAPH" -> validateGraph(request);
            case "DISCOVER_METHOD_IMPLEMENTATIONS" -> methodTargetPayload(
                    require(request, SemanticDtos.DiscoverMethodImplementationsFollowUpRequest.class).declarationTarget());
            case "RESOLVE_CONCEPT" -> conceptIdentity(
                    require(request, SemanticDtos.IdentityFollowUpRequest.class).identity());
            case "GET_TYPE_MEMBERS", "DISCOVER_TYPE_MEMBERS" -> typeMembers(
                    require(request, SemanticDtos.TypeMembersFollowUpRequest.class));
            case "DISCOVER_CONCEPTS" -> concepts(require(request, SemanticDtos.DiscoverConceptsFollowUpRequest.class));
            case "DISCOVER_EVENT_LISTENERS" -> listeners(
                    require(request, SemanticDtos.DiscoverEventListenersFollowUpRequest.class));
            case "RESOLVE_SOURCE_SYMBOL" -> sourceSymbol(
                    require(request, SemanticDtos.ResolveSourceSymbolFollowUpRequest.class));
            case "FIND_INTERNAL_REFERENCES" -> internalReferences(request);
            case "GET_SOURCE_SEGMENT" -> sourceSegment(
                    require(request, SemanticDtos.SourceSegmentFollowUpRequest.class));
            case "GET_EVIDENCE_SOURCE" -> evidenceIdentity(
                    require(request, SemanticDtos.IdentityFollowUpRequest.class).identity());
            default -> throw contract("unsupported Semantic follow-up operation");
        }
    }

    private void validateMethodSource(SemanticDtos.AvailableFollowUpRequest request) {
        SemanticDtos.TargetFollowUpRequest target = require(request, SemanticDtos.TargetFollowUpRequest.class);
        methodTargetPayload(requireFollowUpTarget(target.target(), SemanticDtos.MethodTargetPayload.class));
        if (target.depth().isPresent() || target.offset().isPresent() || target.limit().isPresent()) {
            throw contract("method source target follow-up has mixed fields");
        }
    }

    private void validateGraph(SemanticDtos.AvailableFollowUpRequest request) {
        SemanticDtos.TargetFollowUpRequest target = require(request, SemanticDtos.TargetFollowUpRequest.class);
        methodTargetPayload(requireFollowUpTarget(target.target(), SemanticDtos.MethodTargetPayload.class));
        int depth = target.depth().orElseThrow(() -> contract("call graph depth is required"));
        range(depth, 1, 2, "call graph depth");
        if (target.offset().isPresent() || target.limit().isPresent()) {
            throw contract("call graph target follow-up has mixed fields");
        }
    }

    private void internalReferences(SemanticDtos.AvailableFollowUpRequest request) {
        SemanticDtos.TargetFollowUpRequest target = require(request, SemanticDtos.TargetFollowUpRequest.class);
        SemanticDtos.InternalReferenceFollowUpTarget internal = requireFollowUpTarget(
                target.target(), SemanticDtos.InternalReferenceFollowUpTarget.class);
        internalReferenceTarget(internal);
        if (target.depth().isPresent()) {
            throw contract("internal references target follow-up has mixed fields");
        }
        minimum(target.offset().orElseThrow(() -> contract("reference offset is required")), 0, "reference offset");
        range(target.limit().orElseThrow(() -> contract("reference limit is required")), 1, 100, "reference limit");
    }

    private void typeMembers(SemanticDtos.TypeMembersFollowUpRequest request) {
        sourceTypePayload(request.sourceType());
        memberKinds(request.memberKinds());
        request.namePrefix().ifPresent(value -> nonblank(value, "member name prefix"));
        page(request.offset(), request.limit(), "type members");
    }

    private void concepts(SemanticDtos.DiscoverConceptsFollowUpRequest request) {
        List<SemanticDtos.ConceptSearchTermPayload> terms = requiredList(request.terms(), "concept search term");
        if (terms.isEmpty() || terms.size() > 4) {
            throw contract("concept search terms are outside their supported range");
        }
        for (SemanticDtos.ConceptSearchTermPayload term : terms) {
            String value = requiredString(term.value(), "concept search term value");
            if (value.length() < 2 || value.length() > 128) {
                throw contract("concept search term value is outside its supported range");
            }
            enumValue(term.matchMode(), Set.of("TOKEN_EXACT", "TOKEN_PREFIX"), "concept search term match mode");
        }
        conceptKinds(request.kinds());
        enumValue(request.operator(), Set.of("ALL"), "concept operator");
        request.packagePrefix().ifPresent(value -> nonblank(value, "concept package prefix"));
        page(request.offset(), request.limit(), "concepts");
    }

    private void listeners(SemanticDtos.DiscoverEventListenersFollowUpRequest request) {
        nonblank(request.eventType(), "event type");
        page(request.offset(), request.limit(), "event listeners");
    }

    private void sourceSymbol(SemanticDtos.ResolveSourceSymbolFollowUpRequest request) {
        sourceSymbolContext(request.context());
        nonblank(request.symbol(), "source symbol");
        request.position().ifPresent(value -> position(value, "source symbol position"));
    }

    private void sourceSegment(SemanticDtos.SourceSegmentFollowUpRequest request) {
        sourceRange(request.location(), "source segment location");
        range(request.contextLines(), 0, 20, "source segment context lines");
    }

    private void conceptIdentity(SemanticDtos.FollowUpIdentity identity) {
        if (!(identity instanceof SemanticDtos.ConceptFollowUpIdentity concept)) {
            throw contract("follow-up identity does not match resolve concept operation");
        }
        switch (enumValue(concept.kind(), Set.of("TYPE", "METHOD", "FIELD", "ANNOTATION_USAGE", "TYPE_USAGE",
                "API_ROUTE", "MQ_DESTINATION", "SCHEDULE", "MAPPER_STATEMENT", "MAPPER_STATEMENT_VARIANT"),
                "concept identity kind")) {
            case "TYPE" -> {
                rejectUnexpected(concept, "sourceType");
                sourceTypePayload(requiredOnly(concept.sourceType(), concept, "sourceType"));
            }
            case "METHOD" -> {
                rejectUnexpected(concept, "target");
                methodTargetPayload(requiredOnly(concept.target(), concept, "target"));
            }
            case "FIELD" -> {
                rejectUnexpected(concept, "identity");
                SemanticDtos.ConceptIdentityTargetPayload fieldIdentity = requiredOnly(
                        concept.identity(), concept, "identity");
                if (!(fieldIdentity instanceof SemanticDtos.SourceMemberIdentityPayload.TypeMember)
                        && !(fieldIdentity instanceof SemanticDtos.SourceMemberIdentityPayload.MethodScoped)) {
                    throw contract("concept FIELD identity subtype does not match kind");
                }
                sourceMemberIdentity((SemanticDtos.SourceMemberIdentityPayload) fieldIdentity);
            }
            case "ANNOTATION_USAGE" -> {
                rejectUnexpected(concept, "declaration", "annotationType");
                declarationSubject(requiredOnly(concept.declaration(), concept, "declaration"));
                annotationType(requiredOnly(concept.annotationType(), concept, "annotationType"));
            }
            case "TYPE_USAGE" -> {
                rejectUnexpected(concept, "owner", "location", "path", "referencedType");
                declarationSubject(requiredOnly(concept.owner(), concept, "owner"));
                typeUsageLocation(requiredOnly(concept.location(), concept, "location"));
                for (SemanticDtos.TypeUsagePathPayload path : requiredList(
                        requiredOnly(concept.path(), concept, "path"), "type usage path")) {
                    typeUsagePath(path);
                }
                referencedType(requiredOnly(concept.referencedType(), concept, "referencedType"));
            }
            case "API_ROUTE" -> {
                rejectUnexpected(concept, "target", "httpVerb", "route");
                methodTargetPayload(requiredOnly(concept.target(), concept, "target"));
                nonblank(requiredOnly(concept.httpVerb(), concept, "httpVerb"), "API route HTTP verb");
                nonblank(requiredOnly(concept.route(), concept, "route"), "API route");
            }
            case "MQ_DESTINATION" -> {
                rejectUnexpected(concept, "target", "broker", "destination");
                methodTargetPayload(requiredOnly(concept.target(), concept, "target"));
                nonblank(requiredOnly(concept.broker(), concept, "broker"), "MQ broker");
                nonblank(requiredOnly(concept.destination(), concept, "destination"), "MQ destination");
            }
            case "SCHEDULE" -> {
                rejectUnexpected(concept, "target", "triggerKind", "triggerValue");
                methodTargetPayload(requiredOnly(concept.target(), concept, "target"));
                nonblank(requiredOnly(concept.triggerKind(), concept, "triggerKind"), "schedule trigger kind");
                concept.triggerValue().ifPresent(trigger -> nonblank(trigger, "schedule trigger value"));
            }
            case "MAPPER_STATEMENT" -> {
                rejectUnexpected(concept, "identity");
                mapperStatementKey(requiredConceptIdentity(concept, SemanticDtos.MapperStatementKeyPayload.class));
            }
            case "MAPPER_STATEMENT_VARIANT" -> {
                rejectUnexpected(concept, "identity");
                mapperStatementIdentity(requiredConceptIdentity(concept, SemanticDtos.MapperStatementIdentityPayload.class));
            }
            default -> throw contract("unsupported concept identity kind");
        }
    }

    private void evidenceIdentity(SemanticDtos.FollowUpIdentity identity) {
        if (!(identity instanceof SemanticDtos.EvidenceSourceFollowUpIdentity evidence)) {
            throw contract("follow-up identity does not match evidence source operation");
        }
        String kind = enumValue(evidence.kind(), Set.of("ANNOTATION_SQL", "MAPPER_STATEMENT", "MAPPER_FRAGMENT"),
                "evidence identity kind");
        boolean statementKind = "ANNOTATION_SQL".equals(kind) || "MAPPER_STATEMENT".equals(kind);
        if (statementKind != evidence.statementIdentity().isPresent()
                || "MAPPER_FRAGMENT".equals(kind) != evidence.fragmentIdentity().isPresent()) {
            throw contract("evidence identity does not match kind");
        }
        evidence.statementIdentity().ifPresent(this::mapperStatementIdentity);
        evidence.fragmentIdentity().ifPresent(this::mapperFragmentIdentity);
    }

    private <T> T requiredOnly(Optional<T> value, SemanticDtos.ConceptFollowUpIdentity concept, String name) {
        return value.orElseThrow(() -> contract("concept " + concept.kind() + " identity requires " + name));
    }

    private <T extends SemanticDtos.ConceptIdentityTargetPayload> T requiredConceptIdentity(
            SemanticDtos.ConceptFollowUpIdentity concept, Class<T> type) {
        SemanticDtos.ConceptIdentityTargetPayload identity = requiredOnly(concept.identity(), concept, "identity");
        if (!type.isInstance(identity)) {
            throw contract("concept " + concept.kind() + " identity subtype does not match kind");
        }
        return type.cast(identity);
    }

    private void rejectUnexpected(SemanticDtos.ConceptFollowUpIdentity concept, String... allowed) {
        Set<String> allowedFields = Set.of(allowed);
        boolean unexpected = (concept.sourceType().isPresent() && !allowedFields.contains("sourceType"))
                || (concept.target().isPresent() && !allowedFields.contains("target"))
                || (concept.identity().isPresent() && !allowedFields.contains("identity"))
                || (concept.declaration().isPresent() && !allowedFields.contains("declaration"))
                || (concept.annotationType().isPresent() && !allowedFields.contains("annotationType"))
                || (concept.owner().isPresent() && !allowedFields.contains("owner"))
                || (concept.location().isPresent() && !allowedFields.contains("location"))
                || (concept.path().isPresent() && !allowedFields.contains("path"))
                || (concept.referencedType().isPresent() && !allowedFields.contains("referencedType"))
                || (concept.httpVerb().isPresent() && !allowedFields.contains("httpVerb"))
                || (concept.route().isPresent() && !allowedFields.contains("route"))
                || (concept.broker().isPresent() && !allowedFields.contains("broker"))
                || (concept.destination().isPresent() && !allowedFields.contains("destination"))
                || (concept.triggerKind().isPresent() && !allowedFields.contains("triggerKind"))
                || (concept.triggerValue().isPresent() && !allowedFields.contains("triggerValue"));
        if (unexpected) {
            throw contract("concept identity has fields that do not match kind");
        }
    }

    private void internalReferenceTarget(SemanticDtos.InternalReferenceFollowUpTarget target) {
        String kind = enumValue(target.kind(), Set.of("TYPE", "METHOD", "MEMBER"), "internal reference target kind");
        SemanticDtos.InternalReferenceIdentity identity = target.identity();
        boolean match = ("TYPE".equals(kind) && identity instanceof SemanticDtos.SourceTypeIdentityPayload)
                || ("METHOD".equals(kind) && identity instanceof SemanticDtos.MethodTargetPayload)
                || ("MEMBER".equals(kind) && identity instanceof SemanticDtos.SourceMemberIdentityPayload);
        if (!match) {
            throw contract("internal reference identity does not match kind");
        }
        switch (identity) {
            case SemanticDtos.SourceTypeIdentityPayload value -> sourceTypePayload(value);
            case SemanticDtos.MethodTargetPayload value -> methodTargetPayload(value);
            case SemanticDtos.SourceMemberIdentityPayload value -> sourceMemberIdentity(value);
        }
    }

    private void sourceTypePayload(SemanticDtos.SourceTypeIdentityPayload value) {
        SemanticDtos.SourceTypeIdentityPayload required = requiredObject(value, "source type identity");
        javaTypePayload(required.javaType());
        sourceFile(required.sourceFile());
    }

    private void javaTypePayload(SemanticDtos.JavaTypeIdentityPayload value) {
        SemanticDtos.JavaTypeIdentityPayload required = requiredObject(value, "Java type identity");
        requiredString(required.packageName(), "Java type package");
        javaQualifiedIdentifier(required.className(), true, "Java type class");
    }

    private void methodTargetPayload(SemanticDtos.MethodTargetPayload value) {
        SemanticDtos.MethodTargetPayload required = requiredObject(value, "method target payload");
        sourceTypePayload(required.sourceType());
        javaQualifiedIdentifier(required.methodName(), false, "method target method");
        nonemptyOrEmptyStrings(required.parameterTypes(), "method target parameter type");
    }

    private void sourceMemberIdentity(SemanticDtos.SourceMemberIdentityPayload value) {
        switch (requiredObject(value, "source member identity")) {
            case SemanticDtos.SourceMemberIdentityPayload.TypeMember member -> {
                sourceTypePayload(member.ownerType());
                javaQualifiedIdentifier(member.name(), false, "source member name");
            }
            case SemanticDtos.SourceMemberIdentityPayload.MethodScoped member -> {
                methodTargetPayload(member.declaringMethod());
                textRange(member.declarationRange(), "source member declaration range");
                javaQualifiedIdentifier(member.name(), false, "source member name");
            }
        }
    }

    private void typeMemberOwnedBy(SemanticDtos.SourceMemberIdentityPayload identity,
                                   SemanticDtos.SourceTypeIdentityPayload sourceType, String description) {
        sourceMemberIdentity(identity);
        if (!(identity instanceof SemanticDtos.SourceMemberIdentityPayload.TypeMember member)
                || !sourceType.equals(member.ownerType())) {
            throw contract(description + " identity owner does not match the enclosing source type");
        }
    }

    private void declarationSubject(SemanticDtos.DeclarationSubjectPayload value) {
        switch (requiredObject(value, "declaration subject")) {
            case SemanticDtos.TypeDeclarationSubjectPayload subject -> sourceTypePayload(subject.sourceType());
            case SemanticDtos.ResolvedMethodDeclarationSubjectPayload subject -> methodTargetPayload(subject.target());
            case SemanticDtos.UnresolvedMethodDeclarationSubjectPayload subject -> methodTargetPayload(subject.target());
            case SemanticDtos.FieldDeclarationSubjectPayload subject -> sourceMemberIdentity(subject.identity());
        }
    }

    private void annotationType(SemanticDtos.AnnotationTypePayload value) {
        switch (requiredObject(value, "annotation type")) {
            case SemanticDtos.ResolvedAnnotationTypePayload type -> javaTypePayload(type.javaType());
            case SemanticDtos.UnresolvedAnnotationTypePayload type -> nonblank(type.writtenName(), "annotation written name");
        }
    }

    private void typeUsageLocation(SemanticDtos.TypeUsageLocationPayload value) {
        SemanticDtos.TypeUsageLocationPayload required = requiredObject(value, "type usage location");
        nonblank(required.slot(), "type usage slot");
        minimum(required.index(), 0, "type usage index");
    }

    private void typeUsagePath(SemanticDtos.TypeUsagePathPayload value) {
        switch (requiredObject(value, "type usage path")) {
            case SemanticDtos.TypeArgumentPathPayload path -> minimum(path.index(), 0, "type argument index");
            case SemanticDtos.WildcardExtendsBoundPathPayload ignored -> { }
            case SemanticDtos.WildcardSuperBoundPathPayload ignored -> { }
            case SemanticDtos.TypeVariableBoundPathPayload path -> minimum(path.index(), 0, "type variable bound index");
        }
    }

    private void referencedType(SemanticDtos.ReferencedTypePayload value) {
        SemanticDtos.ReferencedTypePayload required = requiredObject(value, "referenced type");
        javaTypePayload(required.javaType());
        minimum(required.arrayDimensions(), 0, "referenced type array dimensions");
    }

    private void mapperStatementKey(SemanticDtos.MapperStatementKeyPayload value) {
        SemanticDtos.MapperStatementKeyPayload required = requiredObject(value, "mapper statement key");
        nonblank(required.namespace(), "mapper statement namespace");
        nonblank(required.statementId(), "mapper statement ID");
    }

    private void mapperStatementIdentity(SemanticDtos.MapperStatementIdentityPayload value) {
        SemanticDtos.MapperStatementIdentityPayload required = requiredObject(value, "mapper statement identity");
        mapperStatementKey(required.statementKey());
        sourceFile(required.resourcePath());
        required.databaseId().ifPresent(database -> nonblank(database, "mapper database ID"));
        minimum(required.documentOrdinal(), 0, "mapper statement document ordinal");
        enumValue(required.representation(), Set.of("MAPPER_XML_ELEMENT", "ANNOTATION_SQL_TEXT"),
                "mapper statement representation");
    }

    private void mapperFragmentIdentity(SemanticDtos.MapperFragmentIdentityPayload value) {
        SemanticDtos.MapperFragmentIdentityPayload required = requiredObject(value, "mapper fragment identity");
        nonblank(required.namespace(), "mapper fragment namespace");
        nonblank(required.fragmentId(), "mapper fragment ID");
        sourceFile(required.resourcePath());
        minimum(required.documentOrdinal(), 0, "mapper fragment document ordinal");
        enumValue(required.representation(), Set.of("MAPPER_XML_ELEMENT"), "mapper fragment representation");
    }

    private void sourceSymbolContext(SemanticDtos.SourceSymbolContextPayload value) {
        SemanticDtos.SourceSymbolContextPayload required = requiredObject(value, "source symbol context");
        javaTypePayload(required.javaType());
        required.sourceFile().ifPresent(this::sourceFile);
        required.method().ifPresent(method -> {
            javaQualifiedIdentifier(method.name(), false, "source symbol context method");
            nonemptyOrEmptyStrings(method.parameterTypes(), "source symbol context parameter type");
        });
    }

    private void page(Integer offset, Integer limit, String description) {
        minimum(requiredInteger(offset, description + " offset"), 0, description + " offset");
        range(requiredInteger(limit, description + " limit"), 1, 100, description + " limit");
    }

    private void nonemptyStrings(List<String> values, String description) {
        List<String> required = requiredStrings(values, description);
        if (required.isEmpty()) {
            throw contract(description + " list must not be empty");
        }
        for (String value : required) {
            nonblank(value, description);
        }
    }

    private void conceptKinds(List<String> values) {
        List<String> kinds = requiredStrings(values, "concept kind");
        if (kinds.isEmpty() || new HashSet<>(kinds).size() != kinds.size()) {
            throw contract("concept kinds must be nonempty and distinct");
        }
        for (String kind : kinds) {
            enumValue(kind, Set.of("TYPE", "METHOD", "FIELD", "ANNOTATION_USAGE", "TYPE_USAGE", "API_ROUTE",
                    "MQ_DESTINATION", "SCHEDULE", "MAPPER_STATEMENT", "SQL_IDENTIFIER", "CONFIGURATION_KEY",
                    "OUTBOUND_API", "MQ_PUBLISHER", "ERROR_CONTRACT", "ENUM_CONSTANT"), "concept kind");
        }
    }

    private void memberKinds(List<String> values) {
        List<String> kinds = requiredStrings(values, "member kind");
        if (kinds.isEmpty() || new HashSet<>(kinds).size() != kinds.size()) {
            throw contract("member kinds must be nonempty and distinct");
        }
        for (String kind : kinds) {
            enumValue(kind, Set.of("METHOD", "FIELD", "ENUM_CONSTANT", "RECORD_COMPONENT"), "member kind");
        }
    }

    private void nonemptyOrEmptyStrings(List<String> values, String description) {
        for (String value : requiredStrings(values, description)) {
            nonblank(value, description);
        }
    }

    private <T extends SemanticDtos.AvailableFollowUpRequest> T require(
            SemanticDtos.AvailableFollowUpRequest value, Class<T> type) {
        if (!type.isInstance(value)) {
            throw contract("Semantic follow-up request subtype does not match operation");
        }
        return type.cast(value);
    }

    private <T extends SemanticDtos.FollowUpTarget> T requireFollowUpTarget(
            SemanticDtos.FollowUpTarget value, Class<T> type) {
        if (!type.isInstance(value)) {
            throw contract("Semantic follow-up target does not match operation");
        }
        return type.cast(value);
    }

    SemanticDtos.MethodTarget methodTarget(SemanticDtos.MethodTarget target) {
        SemanticDtos.MethodTarget required = requiredObject(target, "method target");
        sourceFile(required.sourceFile());
        requiredString(required.packageName(), "method target package");
        javaQualifiedIdentifier(required.className(), true, "method target class");
        javaQualifiedIdentifier(required.methodName(), false, "method target method");
        for (String parameterType : requiredStrings(required.parameterTypes(), "method target parameter type")) {
            nonblank(parameterType, "method target parameter type");
        }
        return required;
    }

    void repositoryId(String value, String description) {
        String required = requiredString(value, description);
        if (!REPOSITORY_ID.matcher(required).matches()) {
            throw contract(description + " has an invalid format");
        }
    }

    void revision(String value, String description) {
        String required = requiredString(value, description);
        if (!REVISION.matcher(required).matches()) {
            throw contract(description + " has an invalid format");
        }
    }

    private void entryPointMethod(SemanticDtos.EntryPointMethodResponse method) {
        SemanticDtos.EntryPointMethodResponse required = requiredObject(method, "entry point method");
        requiredString(required.name(), "entry point method name");
        requiredString(required.description(), "entry point method description");
        if (required instanceof SemanticDtos.ApiEntryPointMethodResponse api) {
            enumValue(api.type(), Set.of("API"), "API entry point type");
            requiredString(api.apiUrl(), "API entry point URL");
            requiredStrings(api.httpMethods(), "API entry point HTTP method");
            requiredStrings(api.swaggerDescriptions(), "API entry point Swagger description");
        } else if (required instanceof SemanticDtos.MqEntryPointMethodResponse mq) {
            enumValue(mq.type(), Set.of("MQ"), "MQ entry point type");
            enumValue(mq.broker(), Set.of("RABBIT", "KAFKA"), "MQ broker");
            requiredStrings(mq.destinations(), "MQ destination");
        } else if (required instanceof SemanticDtos.ScheduleEntryPointMethodResponse schedule) {
            enumValue(schedule.type(), Set.of("SCHEDULE"), "schedule entry point type");
            enumValue(schedule.triggerKind(), Set.of("CRON", "FIXED_DELAY", "FIXED_RATE", "JOB_HANDLER", "UNSPECIFIED"),
                    "schedule trigger kind");
            requiredString(schedule.triggerValue(), "schedule trigger value");
        } else {
            throw contract("unsupported entry point method type");
        }
        resolution(required.analysisTarget());
        followUps(required.analysisTarget().availableFollowUps());
    }

    private void resolution(SemanticDtos.MethodTargetResolutionResponse resolution) {
        SemanticDtos.MethodTargetResolutionResponse required = requiredObject(resolution, "method target resolution");
        String status = enumValue(required.status(), RESOLUTION_STATUSES, "method target resolution status");
        requiredMethodTargets(required.candidates(), "method target resolution candidate");
        requiredString(required.reasonCode(), "method target resolution reason code");
        if ("RESOLVED".equals(status) && Objects.isNull(required.target())) {
            throw contract("resolved method target must not be null");
        }
        if (Objects.nonNull(required.target())) {
            methodTarget(required.target());
        }
    }

    private void graphTraversal(SemanticDtos.GraphTraversal traversal) {
        SemanticDtos.GraphTraversal required = requiredObject(traversal, "graph traversal");
        range(requiredInteger(required.requestedDepth(), "graph requested depth"), 1, 2, "graph requested depth");
        minimum(requiredInteger(required.expandedNodeCount(), "graph expanded node count"), 0,
                "graph expanded node count");
        minimum(requiredInteger(required.nodeBudget(), "graph node budget"), 0, "graph node budget");
        requiredBoolean(required.rootDirectCallsComplete(), "graph root direct calls complete");
        enumValue(required.limitReason(), LIMIT_REASONS, "graph limit reason");
    }

    private void graphNode(SemanticDtos.GraphNode node) {
        SemanticDtos.GraphNode required = requiredObject(node, "graph node");
        requiredString(required.nodeId(), "graph node ID");
        enumValue(required.contentState(), CONTENT_STATES, "graph node content state");
        enumValue(required.traversalState(), TRAVERSAL_STATES, "graph node traversal state");
        enumValue(required.dispatchKind(), DISPATCH_KINDS, "graph node dispatch kind");
        if (Objects.nonNull(required.target())) {
            methodTarget(required.target());
        }
        if (Objects.nonNull(required.declarationRange())) {
            textRange(required.declarationRange(), "graph declaration range");
        }
    }

    private void graphEdge(SemanticDtos.GraphEdge edge) {
        SemanticDtos.GraphEdge required = requiredObject(edge, "graph edge");
        requiredString(required.callerNodeId(), "graph caller node ID");
        requiredString(required.calleeNodeId(), "graph callee node ID");
        sourceRange(required.callSite(), "graph call site");
        requiredString(required.callExpression(), "graph call expression");
        enumValue(required.resolutionStrategy(), EDGE_STRATEGIES, "graph edge strategy");
        enumValue(required.category(), EDGE_CATEGORIES, "graph edge category");
        requiredStrings(required.evidence(), "graph edge evidence");
        followUps(required.availableFollowUps());
    }

    private void graphWarning(SemanticDtos.GraphWarning warning) {
        SemanticDtos.GraphWarning required = requiredObject(warning, "graph warning");
        enumValue(required.code(), WARNING_CODES, "graph warning code");
        requiredString(required.message(), "graph warning message");
        requiredString(required.nodeId(), "graph warning node ID");
        if (Objects.nonNull(required.callSite())) {
            sourceRange(required.callSite(), "graph warning call site");
        }
        requiredMethodTargets(required.candidates(), "graph warning candidate");
    }

    private void graphError(SemanticDtos.GraphError error) {
        SemanticDtos.GraphError required = requiredObject(error, "graph error");
        enumValue(required.code(), ERROR_CODES, "graph error code");
        requiredString(required.message(), "graph error message");
        requiredString(required.nodeId(), "graph error node ID");
    }

    private void sourceRange(SemanticDtos.SourceRangePayload range, String description) {
        SemanticDtos.SourceRangePayload required = requiredObject(range, description);
        nonblank(required.sourceFile(), description + " source file");
        textRange(required.range(), description + " range");
    }

    private void textRange(SemanticDtos.TextRangePayload range, String description) {
        SemanticDtos.TextRangePayload required = requiredObject(range, description);
        position(required.start(), description + " start");
        position(required.end(), description + " end");
        int startLine = required.start().line();
        int startCharacter = required.start().character();
        int endLine = required.end().line();
        int endCharacter = required.end().character();
        if (endLine < startLine || (endLine == startLine && endCharacter < startCharacter)) {
            throw contract(description + " end must not precede start");
        }
    }

    private void position(SemanticDtos.Position position, String description) {
        SemanticDtos.Position required = requiredObject(position, description);
        minimum(requiredInteger(required.line(), description + " line"), 0, description + " line");
        minimum(requiredInteger(required.character(), description + " character"), 0, description + " character");
    }

    private void sourceFile(String value) {
        String required = requiredString(value, "method target source file");
        nonblank(required, "method target source file");
        if (required.length() > 1_024 || hasControlCharacter(required) || endsWithTerminalWhitespace(required)
                || !normalizedRelativePath(required)) {
            throw contract("method target source file has an invalid format");
        }
    }

    private boolean normalizedRelativePath(String value) {
        if (value.startsWith("/") || value.startsWith("\\") || value.contains("\\")) {
            return false;
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x1f || (character >= 0x7f && character <= 0x9f)) {
                return true;
            }
        }
        return false;
    }

    private boolean endsWithTerminalWhitespace(String value) {
        int codePoint = value.codePointBefore(value.length());
        return Character.isWhitespace(codePoint) || codePoint == 0x00a0 || codePoint == 0x1680 || codePoint == 0x2007
                || codePoint == 0x202f;
    }

    private void javaQualifiedIdentifier(String value, boolean allowsDots, String description) {
        String required = requiredString(value, description);
        if (required.length() > 255 || (!allowsDots && required.indexOf('.') >= 0)) {
            throw contract(description + " has an invalid format");
        }
        String[] parts = allowsDots ? required.split("\\.", -1) : new String[]{required};
        for (String part : parts) {
            if (part.isEmpty() || !isJavaIdentifier(part)) {
                throw contract(description + " has an invalid format");
            }
        }
    }

    private boolean isJavaIdentifier(String value) {
        int first = value.codePointAt(0);
        if (!(Character.isLetter(first) || Character.getType(first) == Character.LETTER_NUMBER
                || Character.getType(first) == Character.CURRENCY_SYMBOL || Character.getType(first) == Character.CONNECTOR_PUNCTUATION)) {
            return false;
        }
        for (int index = Character.charCount(first); index < value.length();) {
            int codePoint = value.codePointAt(index);
            int type = Character.getType(codePoint);
            if (!(Character.isLetter(codePoint) || type == Character.LETTER_NUMBER || type == Character.CURRENCY_SYMBOL
                    || type == Character.CONNECTOR_PUNCTUATION || type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK || Character.isDigit(codePoint))) {
                return false;
            }
            index += Character.charCount(codePoint);
        }
        return true;
    }

    private void optionalRepositoryId(String value, String description) {
        if (Objects.nonNull(value)) {
            repositoryId(value, description);
        }
    }

    private void optionalRevision(String value, String description) {
        if (Objects.nonNull(value)) {
            revision(value, description);
        }
    }

    private List<SemanticDtos.MethodTarget> requiredMethodTargets(List<SemanticDtos.MethodTarget> values, String description) {
        List<SemanticDtos.MethodTarget> required = requiredList(values, description);
        for (SemanticDtos.MethodTarget value : required) {
            methodTarget(value);
        }
        return required;
    }

    private List<String> requiredStrings(List<String> values, String description) {
        return requiredList(values, description);
    }

    private String enumValue(String value, Set<String> supported, String description) {
        String required = requiredString(value, description);
        if (!supported.contains(required)) {
            throw contract("unsupported " + description);
        }
        return required;
    }

    private String requiredString(String value, String description) {
        if (Objects.isNull(value)) {
            throw contract(description + " must not be null");
        }
        return value;
    }

    private void nonblank(String value, String description) {
        if (!StringUtils.hasText(value)) {
            throw contract(description + " must be nonblank");
        }
    }

    private void range(int value, int minimum, int maximum, String description) {
        if (value < minimum || value > maximum) {
            throw contract(description + " is outside its supported range");
        }
    }

    private void minimum(int value, int minimum, String description) {
        if (value < minimum) {
            throw contract(description + " is outside its supported range");
        }
    }

    private int requiredInteger(Integer value, String description) {
        return requiredObject(value, description);
    }

    private boolean requiredBoolean(Boolean value, String description) {
        return requiredObject(value, description);
    }

    private <T> T requiredObject(T value, String description) {
        if (Objects.isNull(value)) {
            throw contract(description + " must not be null");
        }
        return value;
    }

    private <T> List<T> requiredList(List<T> values, String description) {
        if (Objects.isNull(values)) {
            throw contract(description + " list must not be null");
        }
        List<T> result = new ArrayList<>();
        for (T value : values) {
            result.add(requiredObject(value, description));
        }
        return List.copyOf(result);
    }

    private CapabilityExecutionContractException contract(String message) {
        return new CapabilityExecutionContractException(message);
    }
}
