package com.java.system.agent.codeintelligence.semantic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import com.java.system.agent.codeintelligence.CodeIntelligenceQuery;
import com.java.system.agent.capability.spi.CapabilityExecutionContext;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.codeintelligence.planning.EntryPointType;
import com.java.system.agent.codeintelligence.planning.IncomingCallGraphExecutionInput;
import com.java.system.agent.codeintelligence.planning.ListEntryPointsExecutionInput;
import com.java.system.agent.codeintelligence.planning.LookupApiRouteExecutionInput;
import com.java.system.agent.codeintelligence.planning.OutgoingCallGraphExecutionInput;
import com.java.system.agent.codeintelligence.planning.SuggestApiRouteExecutionInput;
import com.java.system.agent.codeintelligence.planning.DiscoverConceptsExecutionInput;
import com.java.system.agent.codeintelligence.planning.ResolveConceptExecutionInput;
import com.java.system.agent.codeintelligence.planning.DiscoverEventListenersExecutionInput;
import com.java.system.agent.codeintelligence.planning.DiscoverMethodImplementationsExecutionInput;
import com.java.system.agent.codeintelligence.planning.DiscoverTypeMembersExecutionInput;
import com.java.system.agent.codeintelligence.planning.FindInternalReferencesExecutionInput;
import com.java.system.agent.codeintelligence.planning.GetEvidenceSourceExecutionInput;
import com.java.system.agent.codeintelligence.planning.GetMethodSourceExecutionInput;
import com.java.system.agent.codeintelligence.planning.GetSourceSegmentExecutionInput;
import com.java.system.agent.codeintelligence.planning.ResolveSourceSymbolExecutionInput;
import com.java.system.agent.answering.domain.candidate.AnalysisCandidate;
import com.java.system.agent.answering.domain.candidate.FollowUpCandidate;
import com.java.system.agent.answering.domain.candidate.RepositoryCandidate;
import com.java.system.agent.answering.domain.candidate.SemanticTargetCandidate;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.port.out.CapabilityExecutionContractException;
import com.java.system.agent.answering.port.out.CapabilityExecutionFailure;
import com.java.system.agent.answering.port.out.CapabilityExecutionFailureCode;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.answering.port.out.CapabilityInvocation;
import com.java.system.agent.answering.port.out.RepositoryCatalogPort;
import com.java.system.agent.answering.port.out.RepositoryDescriptor;
import com.java.system.agent.answering.port.out.RepositoryRevisionContractException;
import com.java.system.agent.answering.port.out.RepositoryRevisionFailure;
import com.java.system.agent.answering.port.out.RepositoryRevisionFailureCode;
import com.java.system.agent.answering.port.out.RepositoryRevisionPort;
import com.java.system.agent.answering.port.out.RepositoryRevisionResult;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import jakarta.validation.Validation;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 以已設定的 immutable RestClient 呼叫 Java Semantic Service 七個 read-only v1 endpoint
 */
public final class JavaSemanticServiceHttpAdapter implements RepositoryCatalogPort, RepositoryRevisionPort {

    private static final String REPOSITORIES_OPERATION = "java-semantic-service:GET /v1/repositories";
    private static final String REPOSITORY_OPERATION = "java-semantic-service:GET /v1/repositories/{repoId}";
    private static final String ENTRY_POINTS_OPERATION = "java-semantic-service:GET /v1/repositories/{repoId}/entry-points";
    private static final String LOOKUP_OPERATION = "java-semantic-service:POST /v1/api-routes/lookup";
    private static final String SUGGEST_OPERATION = "java-semantic-service:POST /v1/api-routes/suggest";
    private static final String OUTGOING_OPERATION = "java-semantic-service:POST /v1/analyses/call-graphs/outgoing";
    private static final String INCOMING_OPERATION = "java-semantic-service:POST /v1/analyses/call-graphs/incoming";
    private static final String CONCEPTS_OPERATION = "java-semantic-service:POST /v1/discovery/concepts";
    private static final String RESOLVE_CONCEPT_OPERATION = "java-semantic-service:POST /v1/discovery/concepts/resolve";
    private static final String LISTENERS_OPERATION = "java-semantic-service:POST /v1/discovery/event-listeners";
    private static final String IMPLEMENTATIONS_OPERATION = "java-semantic-service:POST /v1/discovery/method-implementations";
    private static final String MEMBERS_OPERATION = "java-semantic-service:POST /v1/discovery/type-members";
    private static final String REFERENCES_OPERATION = "java-semantic-service:POST /v1/discovery/internal-references";
    private static final String EVIDENCE_OPERATION = "java-semantic-service:POST /v1/discovery/evidence-source";
    private static final String METHOD_SOURCE_OPERATION = "java-semantic-service:POST /v1/discovery/method-source";
    private static final String SEGMENT_OPERATION = "java-semantic-service:POST /v1/discovery/source-segment";
    private static final String SYMBOL_OPERATION = "java-semantic-service:POST /v1/discovery/source-symbols/resolve";
    private static final Logger LOGGER = Logger.getLogger(JavaSemanticServiceHttpAdapter.class.getName());

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final JavaSemanticResultMapper resultMapper;
    private final CanonicalCapabilityPayloadCodec payloadCodec;
    private final JavaSemanticErrorMapper errorMapper;

    public JavaSemanticServiceHttpAdapter(RestClient restClient) {
        this(restClient, defaultResultMapper(), defaultPayloadCodec());
    }

    public JavaSemanticServiceHttpAdapter(RestClient restClient, JavaSemanticResultMapper resultMapper,
                                          CanonicalCapabilityPayloadCodec payloadCodec) {
        this(restClient, new ObjectMapper(), resultMapper, payloadCodec);
    }

    JavaSemanticServiceHttpAdapter(RestClient restClient, ObjectMapper objectMapper,
                                   JavaSemanticResultMapper resultMapper, CanonicalCapabilityPayloadCodec payloadCodec) {
        this.restClient = Objects.requireNonNull(restClient, "Java Semantic Service RestClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.resultMapper = Objects.requireNonNull(resultMapper, "result mapper must not be null");
        this.payloadCodec = Objects.requireNonNull(payloadCodec, "canonical capability payload codec must not be null");
        this.errorMapper = new JavaSemanticErrorMapper(resultMapper);
    }

    private static CanonicalCapabilityPayloadCodec defaultPayloadCodec() {
        return new CanonicalCapabilityPayloadCodec(Validation.buildDefaultValidatorFactory().getValidator());
    }

    private static JavaSemanticResultMapper defaultResultMapper() {
        return new JavaSemanticResultMapper();
    }

    @Override
    public List<RepositoryDescriptor> availableRepositories() {
        long startedNanos = System.nanoTime();
        String resultCategory = "CONTRACT_EXCEPTION";
        try {
            List<SemanticDtos.RepositoryStatusResponse> response = restClient.get().uri("/v1/repositories")
                    .retrieve().body(new ParameterizedTypeReference<>() {
                    });
            List<RepositoryDescriptor> repositories = resultMapper.repositories(
                    requiredResponse(response, "repository catalog"));
            resultCategory = "SUCCEEDED";
            return repositories;
        } catch (RestClientResponseException exception) {
            resultCategory = "DEPENDENCY_REQUEST_FAILED";
            throw new IllegalStateException("Java Semantic Service repository catalog request failed", exception);
        } catch (RestClientException exception) {
            resultCategory = "DEPENDENCY_UNAVAILABLE";
            throw new IllegalStateException("Java Semantic Service repository catalog is unavailable", exception);
        } finally {
            logOperation(REPOSITORIES_OPERATION, resultCategory, startedNanos);
        }
    }

    @Override
    public RepositoryRevisionResult currentRevision(RepositoryId repositoryId) {
        Objects.requireNonNull(repositoryId, "repository ID must not be null");
        return observeRevisionOperation(REPOSITORY_OPERATION, () -> currentRevisionInternal(repositoryId));
    }

    private RepositoryRevisionResult currentRevisionInternal(RepositoryId repositoryId) {
        try {
            SemanticDtos.RepositoryStatusResponse response = restClient.get()
                    .uri("/v1/repositories/{repoId}", repositoryId.value()).retrieve()
                    .body(SemanticDtos.RepositoryStatusResponse.class);
            SemanticDtos.RepositoryStatusResponse status = resultMapper.repositoryStatus(requiredResponse(response,
                    "repository status"));
            if (Objects.isNull(status.currentRevision())) {
                return new RepositoryRevisionResult.Failed(new RepositoryRevisionFailure(
                        RepositoryRevisionFailureCode.DEPENDENCY_NOT_READY,
                        "Java Semantic Service repository is not ready", REPOSITORY_OPERATION));
            }
            return new RepositoryRevisionResult.Ready(resultMapper.repositoryRevision(status));
        } catch (RestClientResponseException exception) {
            return errorMapper.revision(errorResponse(exception), REPOSITORY_OPERATION);
        } catch (ResourceAccessException exception) {
            return revisionTransportFailure(exception, REPOSITORY_OPERATION);
        } catch (CapabilityExecutionContractException exception) {
            throw new RepositoryRevisionContractException(exception.getMessage());
        } catch (RestClientException exception) {
            throw new RepositoryRevisionContractException("Java Semantic Service repository response violated its contract");
        }
    }

    public CapabilityExecutionResult listEntryPoints(
            CapabilityExecutionContext context,
            ListEntryPointsExecutionInput input) {
        return observeCapabilityOperation(ENTRY_POINTS_OPERATION, () -> listEntryPointsInternal(context, input));
    }

    private CapabilityExecutionResult listEntryPointsInternal(
            CapabilityExecutionContext context,
            ListEntryPointsExecutionInput input) {
        RepositoryQueryScope repository = selectedRepositoryQueryScope(context);
        try {
            EntryPointType type = input.type();
            SemanticDtos.EntryPointsResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/v1/repositories/{repoId}/entry-points")
                            .queryParam("expectedRevision", repository.expectedRevision().value())
                            .queryParamIfPresent("types", Optional.ofNullable(type).map(entryPointType -> entryPointType.name()))
                            .build(repository.repositoryId().value()))
                    .retrieve().body(SemanticDtos.EntryPointsResponse.class);
            return resultMapper.listEntryPoints(resultInvocation(context), requiredResponse(response, "entry-points"));
        } catch (RestClientResponseException exception) {
            return errorMapper.capability(errorResponse(exception), ENTRY_POINTS_OPERATION);
        } catch (ResourceAccessException exception) {
            return capabilityTransportFailure(exception, ENTRY_POINTS_OPERATION);
        } catch (CapabilityExecutionContractException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw contract("Java Semantic Service entry-points response violated its contract");
        }
    }

    public CapabilityExecutionResult lookupApiRoute(CapabilityExecutionContext context, LookupApiRouteExecutionInput input) {
        return observeCapabilityOperation(LOOKUP_OPERATION, () -> lookupApiRouteInternal(context, input));
    }

    public CapabilityExecutionResult suggestApiRoute(CapabilityExecutionContext context, SuggestApiRouteExecutionInput input) {
        return observeCapabilityOperation(SUGGEST_OPERATION, () -> suggestApiRouteInternal(context, input));
    }

    public CapabilityExecutionResult outgoingCallGraph(CapabilityExecutionContext context, OutgoingCallGraphExecutionInput input) {
        return observeCapabilityOperation(OUTGOING_OPERATION, () -> outgoingCallGraphInternal(context, input));
    }

    private CapabilityExecutionResult outgoingCallGraphInternal(
            CapabilityExecutionContext context,
            OutgoingCallGraphExecutionInput input) {
        SemanticTargetCandidate target = selectedTarget(context);
        try {
            SemanticDtos.AnalyzeOutgoingCallGraphRequest request = new SemanticDtos.AnalyzeOutgoingCallGraphRequest(
                    target.repositoryId().value(), target.analyzedRevision().value(), input.depth(),
                    resultMapper.methodTargetPayload(target.semanticTarget()));
            SemanticDtos.OutgoingCallGraphResponse response = restClient.post()
                    .uri("/v1/analyses/call-graphs/outgoing").body(request).retrieve()
                    .body(SemanticDtos.OutgoingCallGraphResponse.class);
            return resultMapper.outgoingCallGraph(resultInvocation(context), requiredResponse(response, "outgoing call graph"));
        } catch (RestClientResponseException exception) {
            return errorMapper.capability(errorResponse(exception), OUTGOING_OPERATION);
        } catch (ResourceAccessException exception) {
            return capabilityTransportFailure(exception, OUTGOING_OPERATION);
        } catch (CapabilityExecutionContractException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw contract("Java Semantic Service outgoing graph response violated its contract");
        }
    }

    public CapabilityExecutionResult incomingCallGraph(CapabilityExecutionContext context, IncomingCallGraphExecutionInput input) {
        return observeCapabilityOperation(INCOMING_OPERATION, () -> incomingCallGraphInternal(context, input));
    }

    private CapabilityExecutionResult incomingCallGraphInternal(
            CapabilityExecutionContext context,
            IncomingCallGraphExecutionInput input) {
        SemanticTargetCandidate target = selectedTarget(context);
        try {
            SemanticDtos.AnalyzeIncomingCallGraphRequest request = new SemanticDtos.AnalyzeIncomingCallGraphRequest(
                    target.repositoryId().value(), target.analyzedRevision().value(), input.depth(),
                    resultMapper.methodTargetPayload(target.semanticTarget()));
            SemanticDtos.IncomingCallGraphResponse response = restClient.post()
                    .uri("/v1/analyses/call-graphs/incoming").body(request).retrieve()
                    .body(SemanticDtos.IncomingCallGraphResponse.class);
            return resultMapper.incomingCallGraph(resultInvocation(context), requiredResponse(response, "incoming call graph"));
        } catch (RestClientResponseException exception) {
            return errorMapper.capability(errorResponse(exception), INCOMING_OPERATION);
        } catch (ResourceAccessException exception) {
            return capabilityTransportFailure(exception, INCOMING_OPERATION);
        } catch (CapabilityExecutionContractException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw contract("Java Semantic Service incoming graph response violated its contract");
        }
    }

    private CapabilityExecutionResult lookupApiRouteInternal(CapabilityExecutionContext context, LookupApiRouteExecutionInput input) {
        return apiRoute(context, input.apiPath(), input.httpMethod(), Optional.empty(), LOOKUP_OPERATION);
    }

    private CapabilityExecutionResult suggestApiRouteInternal(CapabilityExecutionContext context, SuggestApiRouteExecutionInput input) {
        return apiRoute(context, input.apiPath(), input.httpMethod(), Optional.of(input.limit()), SUGGEST_OPERATION);
    }

    private CapabilityExecutionResult apiRoute(
            CapabilityExecutionContext context,
            String apiPath,
            String httpMethod,
            Optional<Integer> limit,
            String operation) {
        try {
            RepositoryQueryScope repository = selectedRepositoryQueryScope(context);
            SemanticDtos.ApiRouteCandidatesResponse response;
            if (limit.isPresent()) {
                response = restClient.post().uri("/v1/api-routes/suggest")
                        .body(new SemanticDtos.ApiRouteSuggestRequest(apiPath,
                                httpMethod, repository.repositoryId().value(), repository.expectedRevision().value(),
                                limit.orElseThrow()))
                        .retrieve().body(SemanticDtos.ApiRouteCandidatesResponse.class);
            } else {
                response = restClient.post().uri("/v1/api-routes/lookup")
                        .body(new SemanticDtos.ApiRouteLookupRequest(apiPath,
                                httpMethod, repository.repositoryId().value(), repository.expectedRevision().value()))
                        .retrieve().body(SemanticDtos.ApiRouteCandidatesResponse.class);
            }
            return resultMapper.apiRoutes(requiredResponse(response, "API route candidates"));
        } catch (RestClientResponseException exception) {
            return errorMapper.capability(errorResponse(exception), operation);
        } catch (ResourceAccessException exception) {
            return capabilityTransportFailure(exception, operation);
        } catch (NumberFormatException exception) {
            throw contract("runtime supplied an invalid route suggestion limit");
        } catch (CapabilityExecutionContractException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw contract("Java Semantic Service API route response violated its contract");
        }
    }

    public CapabilityExecutionResult discoverConcepts(CapabilityExecutionContext context,
                                                      DiscoverConceptsExecutionInput input) {
        return observeCapabilityOperation(CONCEPTS_OPERATION, () -> discoveryRequest(CONCEPTS_OPERATION, () -> {
            DiscoveryScope scope = discoveryScope(context, CodeIntelligenceQuery.DISCOVER_CONCEPTS, input,
                    DiscoverConceptsExecutionInput.class, RepositoryCandidate.class);
            List<SemanticDtos.ConceptSearchTermPayload> terms = input.terms().stream()
                    .map(term -> new SemanticDtos.ConceptSearchTermPayload(term.value(), term.matchMode())).toList();
            SemanticDtos.DiscoverConceptsFollowUpRequest request = new SemanticDtos.DiscoverConceptsFollowUpRequest(
                    scope.repositoryId().value(), scope.expectedRevision().value(), terms, input.kinds(), "ALL",
                    input.packagePrefix(), input.offset(), input.limit());
            SemanticDtos.DiscoverConceptsResponse response = discoveryPost("/v1/discovery/concepts", request,
                    SemanticDtos.DiscoverConceptsResponse.class, "concept discovery");
            return resultMapper.discoverConcepts(scope.repositoryId(), scope.expectedRevision(), response);
        }));
    }

    public CapabilityExecutionResult resolveConcept(CapabilityExecutionContext context, ResolveConceptExecutionInput input) {
        return observeCapabilityOperation(RESOLVE_CONCEPT_OPERATION, () -> discoveryRequest(RESOLVE_CONCEPT_OPERATION, () -> {
            DiscoveryScope scope = followUpScope(context, CodeIntelligenceQuery.RESOLVE_CONCEPT, input,
                    ResolveConceptExecutionInput.class);
            SemanticDtos.IdentityFollowUpRequest request = new SemanticDtos.IdentityFollowUpRequest(scope.repositoryId().value(),
                    scope.expectedRevision().value(), input.identity());
            SemanticDtos.ResolveConceptResponse response = discoveryPost("/v1/discovery/concepts/resolve", request,
                    SemanticDtos.ResolveConceptResponse.class, "concept resolve");
            return resultMapper.resolveConcept(scope.repositoryId(), scope.expectedRevision(), response);
        }));
    }

    public CapabilityExecutionResult discoverEventListeners(CapabilityExecutionContext context,
                                                            DiscoverEventListenersExecutionInput input) {
        return observeCapabilityOperation(LISTENERS_OPERATION, () -> discoveryRequest(LISTENERS_OPERATION, () -> {
            DiscoveryScope scope = discoveryScope(context, CodeIntelligenceQuery.DISCOVER_EVENT_LISTENERS, input,
                    DiscoverEventListenersExecutionInput.class, RepositoryCandidate.class);
            SemanticDtos.DiscoverEventListenersFollowUpRequest request = new SemanticDtos.DiscoverEventListenersFollowUpRequest(
                    scope.repositoryId().value(), scope.expectedRevision().value(), input.eventType(), input.offset(), input.limit());
            SemanticDtos.DiscoverEventListenersResponse response = discoveryPost("/v1/discovery/event-listeners", request,
                    SemanticDtos.DiscoverEventListenersResponse.class, "event listener discovery");
            return resultMapper.discoverEventListeners(scope.repositoryId(), scope.expectedRevision(), response);
        }));
    }

    public CapabilityExecutionResult discoverMethodImplementations(CapabilityExecutionContext context,
                                                                    DiscoverMethodImplementationsExecutionInput input) {
        return observeCapabilityOperation(IMPLEMENTATIONS_OPERATION, () -> discoveryRequest(IMPLEMENTATIONS_OPERATION, () -> {
            DiscoveryScope scope = discoveryScope(context, CodeIntelligenceQuery.DISCOVER_METHOD_IMPLEMENTATIONS, input,
                    DiscoverMethodImplementationsExecutionInput.class, SemanticTargetCandidate.class);
            SemanticDtos.MethodTargetPayload target = targetFor(scope, input.boundTarget(), "method implementation");
            SemanticDtos.DiscoverMethodImplementationsFollowUpRequest request =
                    new SemanticDtos.DiscoverMethodImplementationsFollowUpRequest(scope.repositoryId().value(),
                            scope.expectedRevision().value(), target);
            SemanticDtos.DiscoverMethodImplementationsResponse response = discoveryPost("/v1/discovery/method-implementations",
                    request, SemanticDtos.DiscoverMethodImplementationsResponse.class, "method implementation discovery");
            if (!target.equals(response.requestedTarget())) {
                throw contract("method implementation response target does not match the requested target");
            }
            return resultMapper.discoverMethodImplementations(scope.repositoryId(), scope.expectedRevision(), response);
        }));
    }

    public CapabilityExecutionResult discoverTypeMembers(CapabilityExecutionContext context, DiscoverTypeMembersExecutionInput input) {
        return observeCapabilityOperation(MEMBERS_OPERATION, () -> discoveryRequest(MEMBERS_OPERATION, () -> {
            DiscoveryScope scope = followUpScope(context, CodeIntelligenceQuery.DISCOVER_TYPE_MEMBERS, input,
                    DiscoverTypeMembersExecutionInput.class);
            SemanticDtos.TypeMembersFollowUpRequest request = new SemanticDtos.TypeMembersFollowUpRequest(scope.repositoryId().value(),
                    scope.expectedRevision().value(), input.sourceType(), input.memberKinds(), input.namePrefix(), input.offset(), input.limit());
            SemanticDtos.DiscoverTypeMembersResponse response = discoveryPost("/v1/discovery/type-members", request,
                    SemanticDtos.DiscoverTypeMembersResponse.class, "type member discovery");
            return resultMapper.discoverTypeMembers(scope.repositoryId(), scope.expectedRevision(), response);
        }));
    }

    public CapabilityExecutionResult findInternalReferences(CapabilityExecutionContext context,
                                                            FindInternalReferencesExecutionInput input) {
        return observeCapabilityOperation(REFERENCES_OPERATION, () -> discoveryRequest(REFERENCES_OPERATION, () -> {
            DiscoveryScope scope = followUpScope(context, CodeIntelligenceQuery.FIND_INTERNAL_REFERENCES, input,
                    FindInternalReferencesExecutionInput.class);
            SemanticDtos.TargetFollowUpRequest request = new SemanticDtos.TargetFollowUpRequest(scope.repositoryId().value(),
                    scope.expectedRevision().value(), input.target(), Optional.empty(), Optional.of(input.offset()), Optional.of(input.limit()));
            SemanticDtos.FindInternalReferencesResponse response = discoveryPost("/v1/discovery/internal-references", request,
                    SemanticDtos.FindInternalReferencesResponse.class, "internal reference discovery");
            return resultMapper.findInternalReferences(scope.repositoryId(), scope.expectedRevision(), response);
        }));
    }

    public CapabilityExecutionResult getEvidenceSource(CapabilityExecutionContext context, GetEvidenceSourceExecutionInput input) {
        return observeCapabilityOperation(EVIDENCE_OPERATION, () -> discoveryRequest(EVIDENCE_OPERATION, () -> {
            DiscoveryScope scope = followUpScope(context, CodeIntelligenceQuery.GET_EVIDENCE_SOURCE, input,
                    GetEvidenceSourceExecutionInput.class);
            SemanticDtos.IdentityFollowUpRequest request = new SemanticDtos.IdentityFollowUpRequest(scope.repositoryId().value(),
                    scope.expectedRevision().value(), input.identity());
            SemanticDtos.EvidenceSourceResponse response = discoveryPost("/v1/discovery/evidence-source", request,
                    SemanticDtos.EvidenceSourceResponse.class, "evidence source");
            return resultMapper.getEvidenceSource(scope.repositoryId(), scope.expectedRevision(), response);
        }));
    }

    public CapabilityExecutionResult getMethodSource(CapabilityExecutionContext context, GetMethodSourceExecutionInput input) {
        return observeCapabilityOperation(METHOD_SOURCE_OPERATION, () -> discoveryRequest(METHOD_SOURCE_OPERATION, () -> {
            DiscoveryScope scope = discoveryScope(context, CodeIntelligenceQuery.GET_METHOD_SOURCE, input,
                    GetMethodSourceExecutionInput.class, SemanticTargetCandidate.class);
            SemanticDtos.MethodTargetPayload target = targetFor(scope, input.boundTarget(), "method source");
            SemanticDtos.TargetFollowUpRequest request = new SemanticDtos.TargetFollowUpRequest(scope.repositoryId().value(),
                    scope.expectedRevision().value(), target, Optional.empty(), Optional.empty(), Optional.empty());
            SemanticDtos.MethodSourceResponse response = discoveryPost("/v1/discovery/method-source", request,
                    SemanticDtos.MethodSourceResponse.class, "method source");
            return resultMapper.getMethodSource(scope.repositoryId(), scope.expectedRevision(), response);
        }));
    }

    public CapabilityExecutionResult getSourceSegment(CapabilityExecutionContext context, GetSourceSegmentExecutionInput input) {
        return observeCapabilityOperation(SEGMENT_OPERATION, () -> discoveryRequest(SEGMENT_OPERATION, () -> {
            DiscoveryScope scope = followUpScope(context, CodeIntelligenceQuery.GET_SOURCE_SEGMENT, input,
                    GetSourceSegmentExecutionInput.class);
            SemanticDtos.SourceSegmentFollowUpRequest request = new SemanticDtos.SourceSegmentFollowUpRequest(scope.repositoryId().value(),
                    scope.expectedRevision().value(), input.location(), input.contextLines());
            SemanticDtos.SourceSegmentResponse response = discoveryPost("/v1/discovery/source-segment", request,
                    SemanticDtos.SourceSegmentResponse.class, "source segment");
            return resultMapper.getSourceSegment(scope.repositoryId(), scope.expectedRevision(), response);
        }));
    }

    public CapabilityExecutionResult resolveSourceSymbol(CapabilityExecutionContext context, ResolveSourceSymbolExecutionInput input) {
        return observeCapabilityOperation(SYMBOL_OPERATION, () -> discoveryRequest(SYMBOL_OPERATION, () -> {
            DiscoveryScope scope = discoveryScope(context, CodeIntelligenceQuery.RESOLVE_SOURCE_SYMBOL, input,
                    ResolveSourceSymbolExecutionInput.class, SemanticTargetCandidate.class);
            SemanticDtos.SourceSymbolContextPayload sourceContext = sourceContextFor(scope, input.boundContext());
            SemanticDtos.ResolveSourceSymbolFollowUpRequest request = new SemanticDtos.ResolveSourceSymbolFollowUpRequest(
                    scope.repositoryId().value(), scope.expectedRevision().value(), sourceContext, input.symbol(), input.position());
            SemanticDtos.ResolveSourceSymbolResponse response = discoveryPost("/v1/discovery/source-symbols/resolve", request,
                    SemanticDtos.ResolveSourceSymbolResponse.class, "source symbol resolve");
            return resultMapper.resolveSourceSymbol(scope.repositoryId(), scope.expectedRevision(), response);
        }));
    }

    private CapabilityExecutionResult discoveryRequest(String operation, Supplier<CapabilityExecutionResult> request) {
        try {
            return request.get();
        } catch (RestClientResponseException exception) {
            return errorMapper.capability(errorResponse(exception), operation);
        } catch (ResourceAccessException exception) {
            return capabilityTransportFailure(exception, operation);
        } catch (CapabilityExecutionContractException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw contract("Java Semantic Service discovery response violated its contract");
        }
    }

    private <R> R discoveryPost(String path, Object request, Class<R> responseType, String description) {
        R response = restClient.post().uri(path).body(request).retrieve().body(responseType);
        return requiredResponse(response, description);
    }

    private <T> DiscoveryScope followUpScope(CapabilityExecutionContext context, CodeIntelligenceQuery query,
                                             T input, Class<T> inputType) {
        return discoveryScope(context, query, input, inputType, FollowUpCandidate.class);
    }

    private <T> DiscoveryScope discoveryScope(CapabilityExecutionContext context, CodeIntelligenceQuery query,
                                              T input, Class<T> inputType,
                                              Class<? extends AnalysisCandidate> directCandidateType) {
        Objects.requireNonNull(context, "capability execution context must not be null");
        Objects.requireNonNull(query, "discovery query must not be null");
        Objects.requireNonNull(input, "discovery input must not be null");
        if (!query.capabilityName().equals(context.capability().name())
                || !query.version().equals(context.capability().version())) {
            throw contract("discovery capability does not match the selected operation");
        }
        if (context.candidates().size() != 1) {
            throw contract("discovery capability requires exactly one candidate");
        }
        AnalysisCandidate selected = context.candidates().getFirst().candidate();
        RepositoryId repositoryId = selected.repositoryId();
        RepositoryRevision expectedRevision = context.expectedRevisions().revisionOf(repositoryId)
                .orElseThrow(() -> contract("discovery repository revision is not pinned"));
        if (selected instanceof FollowUpCandidate followUp) {
            if (!query.capabilityName().equals(followUp.targetCapabilityName())
                    || !query.version().equals(followUp.targetCapabilityVersion())
                    || !expectedRevision.equals(followUp.analyzedRevision())) {
                throw contract("discovery follow-up candidate does not match the requested capability scope");
            }
            T decoded = payloadCodec.decode(followUp.payload(), inputType);
            if (!decoded.equals(input)) {
                throw contract("discovery input does not match the selected follow-up payload");
            }
            return new DiscoveryScope(repositoryId, expectedRevision, selected, true);
        }
        if (!directCandidateType.isInstance(selected)) {
            throw contract("discovery capability candidate type is not supported");
        }
        if (selected instanceof SemanticTargetCandidate target && !expectedRevision.equals(target.analyzedRevision())) {
            throw contract("discovery semantic target revision does not match the expected revision");
        }
        return new DiscoveryScope(repositoryId, expectedRevision, selected, false);
    }

    private SemanticDtos.MethodTargetPayload targetFor(DiscoveryScope scope,
                                                       Optional<SemanticDtos.MethodTargetPayload> boundTarget,
                                                       String description) {
        if (scope.followUp()) {
            return boundTarget.orElseThrow(() -> contract(description + " follow-up target is required"));
        }
        if (boundTarget.isPresent() || !(scope.selected() instanceof SemanticTargetCandidate target)) {
            throw contract(description + " direct candidate must provide exactly one unbound semantic target");
        }
        return resultMapper.methodTargetPayload(target.semanticTarget());
    }

    private SemanticDtos.SourceSymbolContextPayload sourceContextFor(DiscoveryScope scope,
                                                                     Optional<SemanticDtos.SourceSymbolContextPayload> boundContext) {
        if (scope.followUp()) {
            return boundContext.orElseThrow(() -> contract("source symbol follow-up context is required"));
        }
        if (boundContext.isPresent() || !(scope.selected() instanceof SemanticTargetCandidate target)) {
            throw contract("source symbol direct candidate must provide exactly one unbound semantic target");
        }
        SemanticDtos.MethodTargetPayload method = resultMapper.methodTargetPayload(target.semanticTarget());
        return new SemanticDtos.SourceSymbolContextPayload(method.sourceType().javaType(),
                Optional.of(method.sourceType().sourceFile()),
                Optional.of(new SemanticDtos.SourceSymbolMethodContextPayload(method.methodName(), method.parameterTypes())));
    }

    private RepositoryRevisionResult observeRevisionOperation(
            String operation,
            Supplier<RepositoryRevisionResult> request) {
        long startedNanos = System.nanoTime();
        String resultCategory = "CONTRACT_EXCEPTION";
        try {
            RepositoryRevisionResult result = request.get();
            resultCategory = revisionResultCategory(result);
            return result;
        } finally {
            logOperation(operation, resultCategory, startedNanos);
        }
    }

    private CapabilityExecutionResult observeCapabilityOperation(
            String operation,
            Supplier<CapabilityExecutionResult> request) {
        long startedNanos = System.nanoTime();
        String resultCategory = "CONTRACT_EXCEPTION";
        try {
            CapabilityExecutionResult result = request.get();
            resultCategory = capabilityResultCategory(result);
            return result;
        } finally {
            logOperation(operation, resultCategory, startedNanos);
        }
    }

    private static String revisionResultCategory(RepositoryRevisionResult result) {
        if (result instanceof RepositoryRevisionResult.Ready) {
            return "SUCCEEDED";
        }
        return ((RepositoryRevisionResult.Failed) result).failure().code().name();
    }

    private static String capabilityResultCategory(CapabilityExecutionResult result) {
        if (result instanceof CapabilityExecutionResult.Succeeded) {
            return "SUCCEEDED";
        }
        return ((CapabilityExecutionResult.Failed) result).failure().code().name();
    }

    private static void logOperation(String operation, String resultCategory, long startedNanos) {
        Level level = "SUCCEEDED".equals(resultCategory) ? Level.INFO : Level.WARNING;
        LOGGER.log(level, "java semantic operation={0} resultCategory={1} elapsedMs={2}",
                new Object[]{
                        operation,
                        resultCategory,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)});
    }

    private SemanticDtos.ApiErrorResponse errorResponse(RestClientResponseException exception) {
        try {
            SemanticDtos.ApiErrorResponse response = objectMapper.readValue(exception.getResponseBodyAsString(),
                    SemanticDtos.ApiErrorResponse.class);
            return requiredResponse(response, "API error");
        } catch (JsonProcessingException | IllegalArgumentException exceptionCause) {
            throw contract("Java Semantic Service error response violated its contract");
        }
    }

    private CapabilityExecutionResult capabilityTransportFailure(ResourceAccessException exception, String operation) {
        CapabilityExecutionFailureCode code = isTimeout(exception) ? CapabilityExecutionFailureCode.TIMEOUT
                : CapabilityExecutionFailureCode.DEPENDENCY_UNAVAILABLE;
        String description = code == CapabilityExecutionFailureCode.TIMEOUT ? "Java Semantic Service request timed out"
                : "Java Semantic Service is unavailable";
        return new CapabilityExecutionResult.Failed(new CapabilityExecutionFailure(code, description, operation));
    }

    private RepositoryRevisionResult revisionTransportFailure(ResourceAccessException exception, String operation) {
        RepositoryRevisionFailureCode code = isTimeout(exception) ? RepositoryRevisionFailureCode.TIMEOUT
                : RepositoryRevisionFailureCode.DEPENDENCY_UNAVAILABLE;
        String description = code == RepositoryRevisionFailureCode.TIMEOUT ? "Java Semantic Service request timed out"
                : "Java Semantic Service is unavailable";
        return new RepositoryRevisionResult.Failed(new RepositoryRevisionFailure(code, description, operation));
    }

    private boolean isTimeout(ResourceAccessException exception) {
        Throwable cause = exception.getCause();
        return cause instanceof SocketTimeoutException || (Objects.nonNull(cause)
                && StringUtils.hasText(cause.getMessage()) && cause.getMessage().toLowerCase().contains("timeout"));
    }

    private RepositoryCandidate selectedRepository(CapabilityExecutionContext context) {
        Objects.requireNonNull(context, "capability execution context must not be null");
        if (context.candidates().size() != 1
                || !(context.candidates().getFirst().candidate() instanceof RepositoryCandidate repository)) {
            throw contract("capability requires exactly one repository candidate");
        }
        return repository;
    }

    private RepositoryQueryScope selectedRepositoryQueryScope(CapabilityExecutionContext context) {
        RepositoryCandidate repository = selectedRepository(context);
        RepositoryRevision expectedRevision = context.expectedRevisions().revisionOf(repository.repositoryId())
                .orElseThrow(() -> contract("capability repository revision is not pinned"));
        return new RepositoryQueryScope(repository.repositoryId(), expectedRevision);
    }

    private SemanticTargetCandidate selectedTarget(CapabilityExecutionContext context) {
        Objects.requireNonNull(context, "capability execution context must not be null");
        if (context.candidates().size() != 1
                || !(context.candidates().getFirst().candidate() instanceof SemanticTargetCandidate target)) {
            throw contract("call graph requires exactly one semantic target candidate");
        }
        return target;
    }

    private CapabilityInvocation resultInvocation(CapabilityExecutionContext context) {
        return new CapabilityInvocation(context.capability(), context.candidates(), context.question(),
                new CapabilityInputPayload("{}"), context.expectedRevisions());
    }

    private Optional<String> optionalText(String value) {
        return StringUtils.hasText(value) ? Optional.of(value.trim()) : Optional.empty();
    }

    private static <T> T requiredResponse(T response, String description) {
        if (Objects.isNull(response)) {
            throw contract("Java Semantic Service " + description + " response must not be null");
        }
        return response;
    }

    private static CapabilityExecutionContractException contract(String message) {
        return new CapabilityExecutionContractException(message);
    }

    private record RepositoryQueryScope(RepositoryId repositoryId, RepositoryRevision expectedRevision) {
    }

    private record DiscoveryScope(RepositoryId repositoryId, RepositoryRevision expectedRevision,
                                  AnalysisCandidate selected, boolean followUp) {
    }
}
