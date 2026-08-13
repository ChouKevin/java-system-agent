package com.java.system.agent.codeintelligence.semantic;

import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import com.java.system.agent.answering.domain.candidate.AnalysisCandidate;
import com.java.system.agent.answering.domain.candidate.SemanticTargetCandidate;
import com.java.system.agent.answering.domain.observation.CapabilityObservation;
import com.java.system.agent.answering.domain.observation.ObservationCode;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.port.out.CapabilityExecutionContractException;
import com.java.system.agent.answering.port.out.CapabilityExecutionFailure;
import com.java.system.agent.answering.port.out.CapabilityExecutionFailureCode;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.answering.port.out.RepositoryRevisionContractException;
import com.java.system.agent.answering.port.out.RepositoryRevisionFailure;
import com.java.system.agent.answering.port.out.RepositoryRevisionFailureCode;
import com.java.system.agent.answering.port.out.RepositoryRevisionResult;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 將 well-formed Java Semantic Service error 文件映射為 answering 的受信任失敗值
 */
public final class JavaSemanticErrorMapper {

    private static final String SOURCE_SERVICE = "java-semantic-service";

    private final JavaSemanticResultMapper resultMapper;
    private final JavaSemanticProviderSchemaValidator schemaValidator = new JavaSemanticProviderSchemaValidator();

    public JavaSemanticErrorMapper(JavaSemanticResultMapper resultMapper) {
        this.resultMapper = Objects.requireNonNull(resultMapper, "Java semantic result mapper must not be null");
    }

    public CapabilityExecutionResult capability(SemanticDtos.ApiErrorResponse error, String operationSource) {
        schemaValidator.error(error);
        String code = error.errorCode();
        String description = failureDescription(error.message(), code);
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
            case "IMPLEMENTATION_TARGET_UNSUPPORTED" -> failed(CapabilityExecutionFailureCode.CAPABILITY_UNAVAILABLE,
                    description, operationSource);
            case "CONCEPT_KIND_UNAVAILABLE" -> failed(CapabilityExecutionFailureCode.CAPABILITY_UNAVAILABLE,
                    conceptKindUnavailableDescription(error, description), operationSource);
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
        try {
            schemaValidator.error(error);
        } catch (CapabilityExecutionContractException exception) {
            throw new RepositoryRevisionContractException(exception.getMessage());
        }
        String code = error.errorCode();
        String description = failureDescription(error.message(), code);
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
        List<SemanticDtos.MethodTarget> targets = error.candidates();
        List<AnalysisCandidate> candidates = new ArrayList<>();
        for (SemanticDtos.MethodTarget target : targets) {
            candidates.add(new SemanticTargetCandidate(repositoryId.orElseThrow(), revision.orElseThrow(),
                    resultMapper.semanticTarget(target),
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

    private String failureDescription(String value, String code) {
        String sanitized = JavaSemanticResultMapper.failureDescription(value);
        return StringUtils.hasText(sanitized) ? sanitized : "Java Semantic Service reported " + code;
    }

    private String conceptKindUnavailableDescription(SemanticDtos.ApiErrorResponse error, String description) {
        return JavaSemanticResultMapper.failureDescription(description
                + "; unavailable kinds=" + String.join(",", error.unavailableKinds())
                + "; supported kinds=" + String.join(",", error.supportedKinds()));
    }

    private static String required(String value, String description) {
        if (!StringUtils.hasText(value)) {
            throw new CapabilityExecutionContractException(description + " must be nonblank");
        }
        return value.trim();
    }
}
