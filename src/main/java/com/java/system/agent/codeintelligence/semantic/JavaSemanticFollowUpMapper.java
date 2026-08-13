package com.java.system.agent.codeintelligence.semantic;

import com.java.system.agent.answering.domain.candidate.FollowUpCandidate;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.port.out.CapabilityExecutionContractException;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.codeintelligence.CodeIntelligenceQuery;
import com.java.system.agent.codeintelligence.planning.DiscoverConceptsExecutionInput;
import com.java.system.agent.codeintelligence.planning.DiscoverEventListenersExecutionInput;
import com.java.system.agent.codeintelligence.planning.DiscoverMethodImplementationsExecutionInput;
import com.java.system.agent.codeintelligence.planning.DiscoverTypeMembersExecutionInput;
import com.java.system.agent.codeintelligence.planning.FindInternalReferencesExecutionInput;
import com.java.system.agent.codeintelligence.planning.GetEvidenceSourceExecutionInput;
import com.java.system.agent.codeintelligence.planning.GetMethodSourceExecutionInput;
import com.java.system.agent.codeintelligence.planning.GetSourceSegmentExecutionInput;
import com.java.system.agent.codeintelligence.planning.IncomingCallGraphExecutionInput;
import com.java.system.agent.codeintelligence.planning.OutgoingCallGraphExecutionInput;
import com.java.system.agent.codeintelligence.planning.ResolveConceptExecutionInput;
import com.java.system.agent.codeintelligence.planning.ResolveSourceSymbolExecutionInput;
import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import jakarta.validation.Validation;

import java.util.Optional;

/** 將 provider 發行的完整 follow-up 合約封閉映射為 Agent candidate */
public final class JavaSemanticFollowUpMapper {
    private final CanonicalCapabilityPayloadCodec codec;
    private final JavaSemanticProviderSchemaValidator validator = new JavaSemanticProviderSchemaValidator();

    JavaSemanticFollowUpMapper() {
        this(new CanonicalCapabilityPayloadCodec(Validation.buildDefaultValidatorFactory().getValidator()));
    }

    public JavaSemanticFollowUpMapper(CanonicalCapabilityPayloadCodec codec) {
        this.codec = java.util.Objects.requireNonNull(codec, "canonical capability payload codec must not be null");
    }

    FollowUpCandidate map(RepositoryId repositoryId, RepositoryRevision revision, SemanticDtos.AvailableFollowUp followUp) {
        followUp = validator.followUp(followUp);
        verifyScope(repositoryId, revision, followUp);
        return switch (followUp.operation()) {
            case "GET_METHOD_SOURCE" -> methodSourceCandidate(repositoryId, revision, followUp);
            case "ANALYZE_OUTGOING_CALL_GRAPH" -> graphCandidate(repositoryId, revision, CodeIntelligenceQuery.OUTGOING_CALL_GRAPH, followUp);
            case "ANALYZE_INCOMING_CALL_GRAPH" -> graphCandidate(repositoryId, revision, CodeIntelligenceQuery.INCOMING_CALL_GRAPH, followUp);
            case "DISCOVER_METHOD_IMPLEMENTATIONS" -> implementationsCandidate(repositoryId, revision, followUp);
            case "RESOLVE_CONCEPT" -> candidate(repositoryId, revision, CodeIntelligenceQuery.RESOLVE_CONCEPT,
                    new ResolveConceptExecutionInput(conceptIdentity(require(followUp.request(), SemanticDtos.IdentityFollowUpRequest.class).identity())));
            case "GET_TYPE_MEMBERS", "DISCOVER_TYPE_MEMBERS" -> typeMembersCandidate(repositoryId, revision, followUp);
            case "DISCOVER_CONCEPTS" -> conceptsCandidate(repositoryId, revision, followUp);
            case "DISCOVER_EVENT_LISTENERS" -> listenersCandidate(repositoryId, revision, followUp);
            case "RESOLVE_SOURCE_SYMBOL" -> sourceSymbolCandidate(repositoryId, revision, followUp);
            case "FIND_INTERNAL_REFERENCES" -> referencesCandidate(repositoryId, revision, followUp);
            case "GET_SOURCE_SEGMENT" -> segmentCandidate(repositoryId, revision, followUp);
            case "GET_EVIDENCE_SOURCE" -> candidate(repositoryId, revision, CodeIntelligenceQuery.GET_EVIDENCE_SOURCE,
                    new GetEvidenceSourceExecutionInput(evidenceIdentity(require(followUp.request(), SemanticDtos.IdentityFollowUpRequest.class).identity())));
            default -> throw contract("unsupported Semantic follow-up operation");
        };
    }

    private FollowUpCandidate graphCandidate(RepositoryId repositoryId, RepositoryRevision revision,
                                              CodeIntelligenceQuery query, SemanticDtos.AvailableFollowUp followUp) {
        SemanticDtos.TargetFollowUpRequest request = require(followUp.request(), SemanticDtos.TargetFollowUpRequest.class);
        SemanticDtos.MethodTargetPayload target = methodTarget(request.target(), "call graph");
        Object execution = query == CodeIntelligenceQuery.OUTGOING_CALL_GRAPH
                ? new OutgoingCallGraphExecutionInput(requiredOptional(request.depth(), "call graph depth"), target)
                : new IncomingCallGraphExecutionInput(requiredOptional(request.depth(), "call graph depth"), target);
        return candidate(repositoryId, revision, query, execution, methodTargetDescription(target));
    }

    private FollowUpCandidate methodSourceCandidate(RepositoryId repositoryId, RepositoryRevision revision,
                                                    SemanticDtos.AvailableFollowUp followUp) {
        SemanticDtos.TargetFollowUpRequest request = require(followUp.request(), SemanticDtos.TargetFollowUpRequest.class);
        SemanticDtos.MethodTargetPayload target = methodTarget(request.target(), "method source");
        return candidate(repositoryId, revision, CodeIntelligenceQuery.GET_METHOD_SOURCE,
                new GetMethodSourceExecutionInput(target), methodTargetDescription(target));
    }

    private FollowUpCandidate implementationsCandidate(RepositoryId repositoryId, RepositoryRevision revision,
                                                        SemanticDtos.AvailableFollowUp followUp) {
        SemanticDtos.DiscoverMethodImplementationsFollowUpRequest request = require(followUp.request(),
                SemanticDtos.DiscoverMethodImplementationsFollowUpRequest.class);
        SemanticDtos.MethodTargetPayload target = request.declarationTarget();
        return candidate(repositoryId, revision, CodeIntelligenceQuery.DISCOVER_METHOD_IMPLEMENTATIONS,
                new DiscoverMethodImplementationsExecutionInput(target), methodTargetDescription(target));
    }

    private FollowUpCandidate typeMembersCandidate(RepositoryId repositoryId, RepositoryRevision revision,
                                                    SemanticDtos.AvailableFollowUp followUp) {
        SemanticDtos.TypeMembersFollowUpRequest request = require(followUp.request(), SemanticDtos.TypeMembersFollowUpRequest.class);
        return candidate(repositoryId, revision, CodeIntelligenceQuery.DISCOVER_TYPE_MEMBERS,
                new DiscoverTypeMembersExecutionInput(request.sourceType(), request.memberKinds(), request.namePrefix(),
                        request.offset(), request.limit()), sourceTypeDescription(request.sourceType()));
    }

    private FollowUpCandidate conceptsCandidate(RepositoryId repositoryId, RepositoryRevision revision, SemanticDtos.AvailableFollowUp followUp) {
        SemanticDtos.DiscoverConceptsFollowUpRequest request = require(followUp.request(), SemanticDtos.DiscoverConceptsFollowUpRequest.class);
        java.util.List<DiscoverConceptsExecutionInput.Term> terms = request.terms().stream()
                .map(term -> new DiscoverConceptsExecutionInput.Term(term.value(), term.matchMode())).toList();
        return candidate(repositoryId, revision, CodeIntelligenceQuery.DISCOVER_CONCEPTS,
                new DiscoverConceptsExecutionInput(terms, request.kinds(), request.packagePrefix(), request.offset(), request.limit()));
    }

    private FollowUpCandidate listenersCandidate(RepositoryId repositoryId, RepositoryRevision revision, SemanticDtos.AvailableFollowUp followUp) {
        SemanticDtos.DiscoverEventListenersFollowUpRequest request = require(followUp.request(), SemanticDtos.DiscoverEventListenersFollowUpRequest.class);
        return candidate(repositoryId, revision, CodeIntelligenceQuery.DISCOVER_EVENT_LISTENERS,
                new DiscoverEventListenersExecutionInput(request.eventType(), request.offset(), request.limit()));
    }

    private FollowUpCandidate sourceSymbolCandidate(RepositoryId repositoryId, RepositoryRevision revision, SemanticDtos.AvailableFollowUp followUp) {
        SemanticDtos.ResolveSourceSymbolFollowUpRequest request = require(followUp.request(), SemanticDtos.ResolveSourceSymbolFollowUpRequest.class);
        return candidate(repositoryId, revision, CodeIntelligenceQuery.RESOLVE_SOURCE_SYMBOL,
                new ResolveSourceSymbolExecutionInput(request.symbol(), request.position(), request.context()));
    }

    private FollowUpCandidate referencesCandidate(RepositoryId repositoryId, RepositoryRevision revision, SemanticDtos.AvailableFollowUp followUp) {
        SemanticDtos.TargetFollowUpRequest request = require(followUp.request(), SemanticDtos.TargetFollowUpRequest.class);
        SemanticDtos.InternalReferenceFollowUpTarget target = internalReferenceTarget(request.target());
        return candidate(repositoryId, revision, CodeIntelligenceQuery.FIND_INTERNAL_REFERENCES,
                new FindInternalReferencesExecutionInput(target, requiredOptional(request.offset(), "reference offset"),
                        requiredOptional(request.limit(), "reference limit")), internalReferenceTargetDescription(target));
    }

    private FollowUpCandidate segmentCandidate(RepositoryId repositoryId, RepositoryRevision revision, SemanticDtos.AvailableFollowUp followUp) {
        SemanticDtos.SourceSegmentFollowUpRequest request = require(followUp.request(), SemanticDtos.SourceSegmentFollowUpRequest.class);
        return candidate(repositoryId, revision, CodeIntelligenceQuery.GET_SOURCE_SEGMENT,
                new GetSourceSegmentExecutionInput(request.location(), request.contextLines()));
    }

    private FollowUpCandidate candidate(RepositoryId repositoryId, RepositoryRevision revision, CodeIntelligenceQuery query, Object input) {
        return new FollowUpCandidate(repositoryId, revision, query.capabilityName(), query.version(), codec.encode(input),
                "Semantic follow-up " + query.name());
    }

    private FollowUpCandidate candidate(RepositoryId repositoryId, RepositoryRevision revision,
                                        CodeIntelligenceQuery query, Object input, String targetDescription) {
        return new FollowUpCandidate(repositoryId, revision, query.capabilityName(), query.version(), codec.encode(input),
                "Semantic follow-up " + query.name() + " target=" + targetDescription);
    }

    private static String methodTargetDescription(SemanticDtos.MethodTargetPayload target) {
        return sourceTypeDescription(target.sourceType()) + "#" + target.methodName()
                + "(" + String.join(",", target.parameterTypes()) + ")";
    }

    private static String sourceTypeDescription(SemanticDtos.SourceTypeIdentityPayload sourceType) {
        SemanticDtos.JavaTypeIdentityPayload javaType = sourceType.javaType();
        return javaType.packageName().isBlank()
                ? javaType.className()
                : javaType.packageName() + "." + javaType.className();
    }

    private static String internalReferenceTargetDescription(SemanticDtos.InternalReferenceFollowUpTarget target) {
        String identity = switch (target.identity()) {
            case SemanticDtos.SourceTypeIdentityPayload sourceType -> sourceTypeDescription(sourceType);
            case SemanticDtos.MethodTargetPayload method -> methodTargetDescription(method);
            case SemanticDtos.SourceMemberIdentityPayload.TypeMember member ->
                    sourceTypeDescription(member.ownerType()) + "#" + member.name();
            case SemanticDtos.SourceMemberIdentityPayload.MethodScoped member ->
                    methodTargetDescription(member.declaringMethod()) + "::" + member.name();
        };
        return target.kind() + " " + identity;
    }

    private void verifyScope(RepositoryId repositoryId, RepositoryRevision revision, SemanticDtos.AvailableFollowUp followUp) {
        SemanticDtos.FollowUpApi expected = expectedApi(followUp.operation());
        if (!expected.equals(followUp.api())) {
            throw contract("Semantic follow-up API does not match operation");
        }
        String requestRepository = requestRepository(followUp.request());
        String requestRevision = requestRevision(followUp.request());
        if (!repositoryId.value().equals(requestRepository) || !revision.value().equals(requestRevision)) {
            throw contract("Semantic follow-up request scope does not match candidate scope");
        }
        verifyTargetShape(followUp.operation(), followUp.request());
    }

    private void verifyTargetShape(String operation, SemanticDtos.AvailableFollowUpRequest request) {
        if (!(request instanceof SemanticDtos.TargetFollowUpRequest targetRequest)) {
            return;
        }
        boolean noPaging = targetRequest.offset().isEmpty() && targetRequest.limit().isEmpty();
        if ("GET_METHOD_SOURCE".equals(operation)) {
            methodTarget(targetRequest.target(), "method source");
            if (targetRequest.depth().isPresent() || !noPaging) {
                throw contract("method source target follow-up has mixed fields");
            }
            return;
        }
        if ("ANALYZE_OUTGOING_CALL_GRAPH".equals(operation) || "ANALYZE_INCOMING_CALL_GRAPH".equals(operation)) {
            methodTarget(targetRequest.target(), "call graph");
            if (targetRequest.depth().isEmpty() || !noPaging) {
                throw contract("call graph target follow-up has mixed fields");
            }
            return;
        }
        if ("FIND_INTERNAL_REFERENCES".equals(operation)) {
            internalReferenceTarget(targetRequest.target());
            if (targetRequest.depth().isPresent() || targetRequest.offset().isEmpty() || targetRequest.limit().isEmpty()) {
                throw contract("internal reference target follow-up has mixed fields");
            }
            return;
        }
        throw contract("target follow-up request subtype does not match operation");
    }

    private SemanticDtos.FollowUpApi expectedApi(String operation) {
        return switch (operation) {
            case "GET_METHOD_SOURCE" -> new SemanticDtos.FollowUpApi("POST", "/v1/discovery/method-source", "getMethodSource");
            case "ANALYZE_OUTGOING_CALL_GRAPH" -> new SemanticDtos.FollowUpApi("POST", "/v1/analyses/call-graphs/outgoing", "analyzeOutgoingCallGraph");
            case "ANALYZE_INCOMING_CALL_GRAPH" -> new SemanticDtos.FollowUpApi("POST", "/v1/analyses/call-graphs/incoming", "analyzeIncomingCallGraph");
            case "DISCOVER_METHOD_IMPLEMENTATIONS" -> new SemanticDtos.FollowUpApi("POST", "/v1/discovery/method-implementations", "discoverMethodImplementations");
            case "RESOLVE_CONCEPT" -> new SemanticDtos.FollowUpApi("POST", "/v1/discovery/concepts/resolve", "resolveConcept");
            case "GET_TYPE_MEMBERS", "DISCOVER_TYPE_MEMBERS" -> new SemanticDtos.FollowUpApi("POST", "/v1/discovery/type-members", "discoverTypeMembers");
            case "DISCOVER_CONCEPTS" -> new SemanticDtos.FollowUpApi("POST", "/v1/discovery/concepts", "discoverConcepts");
            case "DISCOVER_EVENT_LISTENERS" -> new SemanticDtos.FollowUpApi("POST", "/v1/discovery/event-listeners", "discoverEventListeners");
            case "RESOLVE_SOURCE_SYMBOL" -> new SemanticDtos.FollowUpApi("POST", "/v1/discovery/source-symbols/resolve", "resolveSourceSymbol");
            case "FIND_INTERNAL_REFERENCES" -> new SemanticDtos.FollowUpApi("POST", "/v1/discovery/internal-references", "findInternalReferences");
            case "GET_SOURCE_SEGMENT" -> new SemanticDtos.FollowUpApi("POST", "/v1/discovery/source-segment", "getSourceSegment");
            case "GET_EVIDENCE_SOURCE" -> new SemanticDtos.FollowUpApi("POST", "/v1/discovery/evidence-source", "getEvidenceSource");
            default -> throw contract("unsupported Semantic follow-up operation");
        };
    }

    private String requestRepository(SemanticDtos.AvailableFollowUpRequest request) {
        return switch (request) {
            case SemanticDtos.TargetFollowUpRequest value -> value.repoId();
            case SemanticDtos.DiscoverMethodImplementationsFollowUpRequest value -> value.repoId();
            case SemanticDtos.IdentityFollowUpRequest value -> value.repoId();
            case SemanticDtos.TypeMembersFollowUpRequest value -> value.repoId();
            case SemanticDtos.DiscoverConceptsFollowUpRequest value -> value.repoId();
            case SemanticDtos.DiscoverEventListenersFollowUpRequest value -> value.repoId();
            case SemanticDtos.ResolveSourceSymbolFollowUpRequest value -> value.repoId();
            case SemanticDtos.SourceSegmentFollowUpRequest value -> value.repoId();
        };
    }

    private String requestRevision(SemanticDtos.AvailableFollowUpRequest request) {
        return switch (request) {
            case SemanticDtos.TargetFollowUpRequest value -> value.expectedRevision();
            case SemanticDtos.DiscoverMethodImplementationsFollowUpRequest value -> value.expectedRevision();
            case SemanticDtos.IdentityFollowUpRequest value -> value.expectedRevision();
            case SemanticDtos.TypeMembersFollowUpRequest value -> value.expectedRevision();
            case SemanticDtos.DiscoverConceptsFollowUpRequest value -> value.expectedRevision();
            case SemanticDtos.DiscoverEventListenersFollowUpRequest value -> value.expectedRevision();
            case SemanticDtos.ResolveSourceSymbolFollowUpRequest value -> value.expectedRevision();
            case SemanticDtos.SourceSegmentFollowUpRequest value -> value.expectedRevision();
        };
    }

    private <T extends SemanticDtos.AvailableFollowUpRequest> T require(SemanticDtos.AvailableFollowUpRequest request, Class<T> type) {
        if (!type.isInstance(request)) {
            throw contract("Semantic follow-up request subtype does not match operation");
        }
        return type.cast(request);
    }

    private SemanticDtos.ConceptFollowUpIdentity conceptIdentity(SemanticDtos.FollowUpIdentity identity) {
        if (!(identity instanceof SemanticDtos.ConceptFollowUpIdentity concept)) {
            throw contract("Semantic follow-up identity does not match resolve concept operation");
        }
        return concept;
    }

    private SemanticDtos.EvidenceSourceFollowUpIdentity evidenceIdentity(SemanticDtos.FollowUpIdentity identity) {
        if (!(identity instanceof SemanticDtos.EvidenceSourceFollowUpIdentity evidence)) {
            throw contract("Semantic follow-up identity does not match evidence source operation");
        }
        return evidence;
    }

    private SemanticDtos.MethodTargetPayload methodTarget(SemanticDtos.FollowUpTarget target, String operation) {
        if (!(target instanceof SemanticDtos.MethodTargetPayload methodTarget)) {
            throw contract("Semantic follow-up target does not match " + operation + " operation");
        }
        return methodTarget;
    }

    private SemanticDtos.InternalReferenceFollowUpTarget internalReferenceTarget(SemanticDtos.FollowUpTarget target) {
        if (!(target instanceof SemanticDtos.InternalReferenceFollowUpTarget referenceTarget)) {
            throw contract("Semantic follow-up target does not match internal references operation");
        }
        return referenceTarget;
    }

    private int requiredOptional(Optional<Integer> value, String description) {
        return value.orElseThrow(() -> contract(description + " is required"));
    }

    private static CapabilityExecutionContractException contract(String message) {
        return new CapabilityExecutionContractException(message);
    }
}
