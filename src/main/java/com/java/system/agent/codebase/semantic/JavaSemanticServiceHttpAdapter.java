package com.java.system.agent.codebase.semantic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.codebase.semantic.dto.SemanticDtos;
import com.java.system.agent.runtime.domain.candidate.IssuedCandidate;
import com.java.system.agent.runtime.domain.candidate.RepositoryCandidate;
import com.java.system.agent.runtime.domain.candidate.SemanticTargetCandidate;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.port.out.CapabilityExecutionContractException;
import com.java.system.agent.runtime.port.out.CapabilityExecutionFailure;
import com.java.system.agent.runtime.port.out.CapabilityExecutionFailureCode;
import com.java.system.agent.runtime.port.out.CapabilityExecutionResult;
import com.java.system.agent.runtime.port.out.CapabilityInvocation;
import com.java.system.agent.runtime.port.out.RepositoryCatalogPort;
import com.java.system.agent.runtime.port.out.RepositoryDescriptor;
import com.java.system.agent.runtime.port.out.RepositoryRevisionContractException;
import com.java.system.agent.runtime.port.out.RepositoryRevisionFailure;
import com.java.system.agent.runtime.port.out.RepositoryRevisionFailureCode;
import com.java.system.agent.runtime.port.out.RepositoryRevisionPort;
import com.java.system.agent.runtime.port.out.RepositoryRevisionResult;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
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
    private static final Logger LOGGER = Logger.getLogger(JavaSemanticServiceHttpAdapter.class.getName());

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final JavaSemanticResultMapper resultMapper;
    private final JavaSemanticErrorMapper errorMapper;

    public JavaSemanticServiceHttpAdapter(RestClient restClient) {
        this(restClient, new ObjectMapper(), new JavaSemanticResultMapper());
    }

    JavaSemanticServiceHttpAdapter(RestClient restClient, ObjectMapper objectMapper,
                                   JavaSemanticResultMapper resultMapper) {
        this.restClient = Objects.requireNonNull(restClient, "Java Semantic Service RestClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.resultMapper = Objects.requireNonNull(resultMapper, "result mapper must not be null");
        this.errorMapper = new JavaSemanticErrorMapper(resultMapper);
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

    public CapabilityExecutionResult listEntryPoints(CapabilityInvocation invocation) {
        return observeCapabilityOperation(ENTRY_POINTS_OPERATION, () -> listEntryPointsInternal(invocation));
    }

    private CapabilityExecutionResult listEntryPointsInternal(CapabilityInvocation invocation) {
        RepositoryCandidate repository = selectedRepository(invocation);
        try {
            String type = invocation.arguments().get("type");
            SemanticDtos.EntryPointsResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/v1/repositories/{repoId}/entry-points")
                            .queryParamIfPresent("types", optionalText(type))
                            .build(repository.repositoryId().value()))
                    .retrieve().body(SemanticDtos.EntryPointsResponse.class);
            return resultMapper.listEntryPoints(invocation, requiredResponse(response, "entry-points"));
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

    public CapabilityExecutionResult lookupApiRoute(CapabilityInvocation invocation) {
        return observeCapabilityOperation(LOOKUP_OPERATION, () -> apiRoute(invocation, LOOKUP_OPERATION, false));
    }

    public CapabilityExecutionResult suggestApiRoute(CapabilityInvocation invocation) {
        return observeCapabilityOperation(SUGGEST_OPERATION, () -> apiRoute(invocation, SUGGEST_OPERATION, true));
    }

    public CapabilityExecutionResult outgoingCallGraph(CapabilityInvocation invocation) {
        return observeCapabilityOperation(OUTGOING_OPERATION, () -> outgoingCallGraphInternal(invocation));
    }

    private CapabilityExecutionResult outgoingCallGraphInternal(CapabilityInvocation invocation) {
        SemanticTargetCandidate target = selectedTarget(invocation);
        try {
            SemanticDtos.AnalyzeOutgoingCallGraphRequest request = new SemanticDtos.AnalyzeOutgoingCallGraphRequest(
                    target.repositoryId().value(), target.analyzedRevision().value(), requestedDepth(invocation),
                    resultMapper.methodTarget(target.semanticTarget()));
            SemanticDtos.OutgoingCallGraphResponse response = restClient.post()
                    .uri("/v1/analyses/call-graphs/outgoing").body(request).retrieve()
                    .body(SemanticDtos.OutgoingCallGraphResponse.class);
            return resultMapper.outgoingCallGraph(invocation, requiredResponse(response, "outgoing call graph"));
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

    public CapabilityExecutionResult incomingCallGraph(CapabilityInvocation invocation) {
        return observeCapabilityOperation(INCOMING_OPERATION, () -> incomingCallGraphInternal(invocation));
    }

    private CapabilityExecutionResult incomingCallGraphInternal(CapabilityInvocation invocation) {
        SemanticTargetCandidate target = selectedTarget(invocation);
        try {
            SemanticDtos.AnalyzeIncomingCallGraphRequest request = new SemanticDtos.AnalyzeIncomingCallGraphRequest(
                    target.repositoryId().value(), target.analyzedRevision().value(), requestedDepth(invocation),
                    resultMapper.methodTarget(target.semanticTarget()));
            SemanticDtos.IncomingCallGraphResponse response = restClient.post()
                    .uri("/v1/analyses/call-graphs/incoming").body(request).retrieve()
                    .body(SemanticDtos.IncomingCallGraphResponse.class);
            return resultMapper.incomingCallGraph(invocation, requiredResponse(response, "incoming call graph"));
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

    private CapabilityExecutionResult apiRoute(CapabilityInvocation invocation, String operation, boolean suggested) {
        try {
            Optional<RepositoryCandidate> repository = optionalRepository(invocation);
            Map<String, String> arguments = invocation.arguments();
            SemanticDtos.ApiRouteCandidatesResponse response;
            if (suggested) {
                response = restClient.post().uri("/v1/api-routes/suggest")
                        .body(new SemanticDtos.ApiRouteSuggestRequest(requiredArgument(arguments, "apiPath"),
                                arguments.get("httpMethod"), repository.map(candidate -> candidate.repositoryId().value())
                                        .orElse(null), Integer.valueOf(requiredArgument(arguments, "limit"))))
                        .retrieve().body(SemanticDtos.ApiRouteCandidatesResponse.class);
            } else {
                response = restClient.post().uri("/v1/api-routes/lookup")
                        .body(new SemanticDtos.ApiRouteLookupRequest(requiredArgument(arguments, "apiPath"),
                                arguments.get("httpMethod"), repository.map(candidate -> candidate.repositoryId().value())
                                        .orElse(null)))
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

    private RepositoryCandidate selectedRepository(CapabilityInvocation invocation) {
        Optional<RepositoryCandidate> selected = optionalRepository(invocation);
        if (selected.isEmpty()) {
            throw contract("capability requires exactly one repository candidate");
        }
        return selected.orElseThrow();
    }

    private Optional<RepositoryCandidate> optionalRepository(CapabilityInvocation invocation) {
        Objects.requireNonNull(invocation, "capability invocation must not be null");
        if (invocation.candidates().isEmpty()) {
            return Optional.empty();
        }
        if (invocation.candidates().size() != 1
                || !(invocation.candidates().getFirst().candidate() instanceof RepositoryCandidate repository)) {
            throw contract("capability repository candidates must contain at most one repository");
        }
        return Optional.of(repository);
    }

    private SemanticTargetCandidate selectedTarget(CapabilityInvocation invocation) {
        Objects.requireNonNull(invocation, "capability invocation must not be null");
        if (invocation.candidates().size() != 1
                || !(invocation.candidates().getFirst().candidate() instanceof SemanticTargetCandidate target)) {
            throw contract("call graph requires exactly one semantic target candidate");
        }
        return target;
    }

    private int requestedDepth(CapabilityInvocation invocation) {
        String depth = invocation.arguments().get("depth");
        return StringUtils.hasText(depth) ? Integer.parseInt(depth) : 2;
    }

    private String requiredArgument(Map<String, String> arguments, String name) {
        String value = arguments.get(name);
        if (!StringUtils.hasText(value)) {
            throw contract("runtime invocation is missing required argument " + name);
        }
        return value.trim();
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
}
