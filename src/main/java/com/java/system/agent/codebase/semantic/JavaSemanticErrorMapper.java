package com.java.system.agent.codebase.semantic;

import com.java.system.agent.codebase.semantic.dto.SemanticDtos;
import com.java.system.agent.runtime.domain.candidate.AnalysisCandidate;
import com.java.system.agent.runtime.domain.candidate.SemanticTargetCandidate;
import com.java.system.agent.runtime.domain.observation.CapabilityObservation;
import com.java.system.agent.runtime.domain.observation.ObservationCode;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.port.out.CapabilityExecutionContractException;
import com.java.system.agent.runtime.port.out.CapabilityExecutionFailure;
import com.java.system.agent.runtime.port.out.CapabilityExecutionFailureCode;
import com.java.system.agent.runtime.port.out.CapabilityExecutionResult;
import com.java.system.agent.runtime.port.out.RepositoryRevisionContractException;
import com.java.system.agent.runtime.port.out.RepositoryRevisionFailure;
import com.java.system.agent.runtime.port.out.RepositoryRevisionFailureCode;
import com.java.system.agent.runtime.port.out.RepositoryRevisionResult;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 將 well-formed Java Semantic Service error 文件映射為 runtime 的受信任失敗值
 */
public final class JavaSemanticErrorMapper {

    private static final String SOURCE_SERVICE = "java-semantic-service";

    private final JavaSemanticResultMapper resultMapper;

    public JavaSemanticErrorMapper(JavaSemanticResultMapper resultMapper) {
        this.resultMapper = Objects.requireNonNull(resultMapper, "Java semantic result mapper must not be null");
    }

    public CapabilityExecutionResult capability(SemanticDtos.ApiErrorResponse error, String operationSource) {
        Objects.requireNonNull(error, "API error response must not be null");
        String code = required(error.errorCode(), "API error code");
        String description = JavaSemanticResultMapper.singleLine(error.message());
        return switch (code) {
            case "REPOSITORY_REVISION_MISMATCH" -> failed(CapabilityExecutionFailureCode.REVISION_CONFLICT,
                    description, operationSource);
            case "REPOSITORY_NOT_READY" -> failed(CapabilityExecutionFailureCode.DEPENDENCY_NOT_READY,
                    description, operationSource);
            case "SEMANTIC_REQUEST_TIMEOUT" -> failed(CapabilityExecutionFailureCode.TIMEOUT,
                    description, operationSource);
            case "SEMANTIC_UNAUTHORIZED", "SEMANTIC_AUTH_DISABLED" -> failed(CapabilityExecutionFailureCode.FORBIDDEN,
                    description, operationSource);
            case "REPOSITORY_NOT_FOUND" -> failed(CapabilityExecutionFailureCode.REPOSITORY_NOT_FOUND,
                    description, operationSource);
            case "SEMANTIC_ENGINE_START_FAILED" -> failed(CapabilityExecutionFailureCode.DEPENDENCY_UNAVAILABLE,
                    description, operationSource);
            case "REQUEST_INVALID", "INTERNAL_ERROR" -> failed(CapabilityExecutionFailureCode.DEPENDENCY_FAILURE,
                    description, operationSource);
            case "SEMANTIC_BINDING_AMBIGUOUS" -> observationResult(ObservationCode.AMBIGUOUS_SEMANTIC_TARGET,
                    description, error);
            case "SEMANTIC_TARGET_NOT_FOUND", "SEMANTIC_BINDING_UNRESOLVED" -> observationResult(
                    ObservationCode.UNRESOLVED_CALL, description, error);
            case "SEMANTIC_PROTOCOL_ERROR" -> throw new CapabilityExecutionContractException(
                    "Java Semantic Service reported a semantic protocol error");
            default -> throw new CapabilityExecutionContractException("unsupported Java Semantic Service error code");
        };
    }

    public RepositoryRevisionResult revision(SemanticDtos.ApiErrorResponse error, String operationSource) {
        Objects.requireNonNull(error, "API error response must not be null");
        String code = required(error.errorCode(), "API error code");
        String description = JavaSemanticResultMapper.singleLine(error.message());
        return switch (code) {
            case "REPOSITORY_NOT_READY" -> revisionFailed(RepositoryRevisionFailureCode.DEPENDENCY_NOT_READY,
                    description, operationSource);
            case "SEMANTIC_REQUEST_TIMEOUT" -> revisionFailed(RepositoryRevisionFailureCode.TIMEOUT,
                    description, operationSource);
            case "SEMANTIC_UNAUTHORIZED", "SEMANTIC_AUTH_DISABLED" -> revisionFailed(RepositoryRevisionFailureCode.FORBIDDEN,
                    description, operationSource);
            case "REPOSITORY_NOT_FOUND" -> revisionFailed(RepositoryRevisionFailureCode.REPOSITORY_NOT_FOUND,
                    description, operationSource);
            case "SEMANTIC_ENGINE_START_FAILED" -> revisionFailed(RepositoryRevisionFailureCode.DEPENDENCY_UNAVAILABLE,
                    description, operationSource);
            case "REPOSITORY_REVISION_MISMATCH", "REQUEST_INVALID", "INTERNAL_ERROR",
                    "SEMANTIC_BINDING_AMBIGUOUS", "SEMANTIC_TARGET_NOT_FOUND", "SEMANTIC_BINDING_UNRESOLVED" ->
                    revisionFailed(RepositoryRevisionFailureCode.DEPENDENCY_FAILURE, description, operationSource);
            case "SEMANTIC_PROTOCOL_ERROR" -> throw new RepositoryRevisionContractException(
                    "Java Semantic Service reported a semantic protocol error");
            default -> throw new RepositoryRevisionContractException("unsupported Java Semantic Service error code");
        };
    }

    private CapabilityExecutionResult observationResult(ObservationCode code, String description,
                                                         SemanticDtos.ApiErrorResponse error) {
        List<AnalysisCandidate> candidates = errorCandidates(error, description);
        CapabilityObservation observation = new CapabilityObservation(code, description, candidates, List.of(),
                SOURCE_SERVICE);
        return new CapabilityExecutionResult.Succeeded(candidates, List.of(), List.of(observation));
    }

    private List<AnalysisCandidate> errorCandidates(SemanticDtos.ApiErrorResponse error, String description) {
        Optional<RepositoryId> repositoryId = repositoryId(error.repoId());
        Optional<RepositoryRevision> revision = revision(error.currentRevision(), error.expectedRevision());
        if (repositoryId.isEmpty() || revision.isEmpty()) {
            return List.of();
        }
        List<SemanticDtos.MethodTarget> targets = Objects.requireNonNullElse(error.candidates(), List.of());
        List<AnalysisCandidate> candidates = new ArrayList<>();
        for (SemanticDtos.MethodTarget target : targets) {
            candidates.add(new SemanticTargetCandidate(repositoryId.orElseThrow(), revision.orElseThrow(),
                    resultMapper.semanticTarget(Objects.requireNonNull(target, "error target must not be null")),
                    description));
        }
        return List.copyOf(candidates);
    }

    private Optional<RepositoryId> repositoryId(String value) {
        return StringUtils.hasText(value) ? Optional.of(new RepositoryId(value)) : Optional.empty();
    }

    private Optional<RepositoryRevision> revision(String currentRevision, String expectedRevision) {
        if (StringUtils.hasText(currentRevision)) {
            return Optional.of(new RepositoryRevision(currentRevision));
        }
        return StringUtils.hasText(expectedRevision) ? Optional.of(new RepositoryRevision(expectedRevision))
                : Optional.empty();
    }

    private CapabilityExecutionResult failed(CapabilityExecutionFailureCode code, String description,
                                             String operationSource) {
        return new CapabilityExecutionResult.Failed(new CapabilityExecutionFailure(code, description,
                required(operationSource, "operation source")));
    }

    private RepositoryRevisionResult revisionFailed(RepositoryRevisionFailureCode code, String description,
                                                    String operationSource) {
        return new RepositoryRevisionResult.Failed(new RepositoryRevisionFailure(code, description,
                required(operationSource, "operation source")));
    }

    private static String required(String value, String description) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(description + " must be nonblank");
        }
        return value.trim();
    }
}
