package com.java.system.agent.answering.application;

import com.java.system.agent.answering.domain.answer.AnswerVerificationMode;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.port.out.AnswerVerificationContext;
import com.java.system.agent.answering.port.out.AnswerVerificationContractException;
import com.java.system.agent.answering.port.out.AnswerVerificationPort;
import com.java.system.agent.answering.port.out.AnswerVerificationResult;
import com.java.system.agent.answering.port.out.AnswerVerificationUnavailableException;
import com.java.system.agent.answering.port.out.CapabilityCatalogPort;
import com.java.system.agent.answering.port.out.CapabilityExecutionContractException;
import com.java.system.agent.answering.port.out.CapabilityExecutionPort;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.answering.port.out.CapabilityInvocation;
import com.java.system.agent.answering.port.out.ExternalExecutionDeferredException;
import com.java.system.agent.answering.port.out.RepositoryCatalogPort;
import com.java.system.agent.answering.port.out.RepositoryDescriptor;
import com.java.system.agent.answering.port.out.RepositoryRevisionContractException;
import com.java.system.agent.answering.port.out.RepositoryRevisionPort;
import com.java.system.agent.answering.port.out.RepositoryRevisionResult;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 記錄 Agent lifecycle 外部讀取與驗證操作的穩定 telemetry 分類
 */
final class AgentLoopTelemetry {

    private static final String CAPABILITY_CATALOG_OPERATION = "CAPABILITY_CATALOG";
    private static final String REPOSITORY_CATALOG_OPERATION = "REPOSITORY_CATALOG";
    private static final String REPOSITORY_REVISION_RESOLUTION_OPERATION = "REPOSITORY_REVISION_RESOLUTION";
    private static final String CAPABILITY_EXECUTION_OPERATION = "CAPABILITY_EXECUTION";
    private static final String SUCCEEDED_RESULT_CATEGORY = "SUCCEEDED";
    private static final String TYPED_FAILURE_RESULT_CATEGORY = "TYPED_FAILURE";
    private static final String CONTRACT_EXCEPTION_RESULT_CATEGORY = "CONTRACT_EXCEPTION";
    private static final String UNEXPECTED_EXCEPTION_RESULT_CATEGORY = "UNEXPECTED_EXCEPTION";
    private static final String INCOMPATIBLE_RESULT_CATEGORY = "INCOMPATIBLE_RESULT";
    private static final Logger LOGGER = Logger.getLogger(AgentLoopTelemetry.class.getName());

    private final CapabilityExecutionPort capabilityExecutionPort;
    private final AnswerVerificationPort verificationPort;
    private final CapabilityCatalogPort capabilityCatalogPort;
    private final RepositoryCatalogPort repositoryCatalogPort;
    private final RepositoryRevisionPort repositoryRevisionPort;

    AgentLoopTelemetry(
            CapabilityExecutionPort capabilityExecutionPort,
            AnswerVerificationPort verificationPort,
            CapabilityCatalogPort capabilityCatalogPort,
            RepositoryCatalogPort repositoryCatalogPort,
            RepositoryRevisionPort repositoryRevisionPort) {
        this.capabilityExecutionPort = Objects.requireNonNull(
                capabilityExecutionPort, "capability execution port must not be null");
        this.verificationPort = Objects.requireNonNull(
                verificationPort, "answer verification port must not be null");
        this.capabilityCatalogPort = Objects.requireNonNull(
                capabilityCatalogPort, "capability catalog port must not be null");
        this.repositoryCatalogPort = Objects.requireNonNull(
                repositoryCatalogPort, "repository catalog port must not be null");
        this.repositoryRevisionPort = Objects.requireNonNull(
                repositoryRevisionPort, "repository revision port must not be null");
    }

    List<CapabilityPolicy> loadCapabilities(AgentRunState state) {
        long startedNanos = System.nanoTime();
        String resultCategory = UNEXPECTED_EXCEPTION_RESULT_CATEGORY;
        try {
            List<CapabilityPolicy> catalog = capabilityCatalogPort.availableCapabilities();
            if (Objects.isNull(catalog)) {
                resultCategory = CONTRACT_EXCEPTION_RESULT_CATEGORY;
            }
            List<CapabilityPolicy> copiedCatalog = List.copyOf(Objects.requireNonNull(
                    catalog, "capability catalog port must return a catalog"));
            resultCategory = SUCCEEDED_RESULT_CATEGORY;
            return copiedCatalog;
        } catch (CapabilityExecutionContractException exception) {
            resultCategory = CONTRACT_EXCEPTION_RESULT_CATEGORY;
            throw exception;
        } finally {
            logLifecycleOperation(state, CAPABILITY_CATALOG_OPERATION, resultCategory, startedNanos);
        }
    }

    List<RepositoryDescriptor> loadRepositories(AgentRunState state) {
        long startedNanos = System.nanoTime();
        String resultCategory = UNEXPECTED_EXCEPTION_RESULT_CATEGORY;
        try {
            List<RepositoryDescriptor> catalog = repositoryCatalogPort.availableRepositories();
            if (Objects.isNull(catalog)) {
                resultCategory = CONTRACT_EXCEPTION_RESULT_CATEGORY;
            }
            List<RepositoryDescriptor> copiedCatalog = List.copyOf(Objects.requireNonNull(
                    catalog, "repository catalog port must return a catalog"));
            resultCategory = SUCCEEDED_RESULT_CATEGORY;
            return copiedCatalog;
        } finally {
            logLifecycleOperation(state, REPOSITORY_CATALOG_OPERATION, resultCategory, startedNanos);
        }
    }

    RepositoryRevisionResult resolveRevision(AgentRunState state, RepositoryId repositoryId) {
        long startedNanos = System.nanoTime();
        String resultCategory = UNEXPECTED_EXCEPTION_RESULT_CATEGORY;
        try {
            RepositoryRevisionResult revisionResult = repositoryRevisionPort.currentRevision(repositoryId);
            if (Objects.isNull(revisionResult)) {
                resultCategory = CONTRACT_EXCEPTION_RESULT_CATEGORY;
            }
            RepositoryRevisionResult result = Objects.requireNonNull(
                    revisionResult, "repository revision port must return a result");
            resultCategory = repositoryRevisionResultCategory(result);
            return result;
        } catch (RepositoryRevisionContractException exception) {
            resultCategory = CONTRACT_EXCEPTION_RESULT_CATEGORY;
            throw exception;
        } finally {
            logLifecycleOperation(
                    state,
                    REPOSITORY_REVISION_RESOLUTION_OPERATION,
                    resultCategory,
                    startedNanos);
        }
    }

    CapabilityExecutionResult executeCapability(AgentRunState state, CapabilityInvocation invocation) {
        long startedNanos = System.nanoTime();
        String resultCategory = UNEXPECTED_EXCEPTION_RESULT_CATEGORY;
        try {
            CapabilityExecutionResult executionResult = capabilityExecutionPort.execute(invocation);
            if (Objects.isNull(executionResult)) {
                resultCategory = CONTRACT_EXCEPTION_RESULT_CATEGORY;
            }
            CapabilityExecutionResult result = Objects.requireNonNull(
                    executionResult, "capability execution port must return a result");
            resultCategory = capabilityExecutionResultCategory(result);
            return result;
        } catch (CapabilityExecutionContractException exception) {
            resultCategory = CONTRACT_EXCEPTION_RESULT_CATEGORY;
            throw exception;
        } finally {
            logLifecycleOperation(state, CAPABILITY_EXECUTION_OPERATION, resultCategory, startedNanos);
        }
    }

    AnswerVerificationResult verifyAnswer(
            AgentRunState state,
            AnswerVerificationMode mode,
            AnswerVerificationContext context) {
        Objects.requireNonNull(state, "agent run state must not be null");
        Objects.requireNonNull(mode, "answer verification mode must not be null");
        Objects.requireNonNull(context, "answer verification context must not be null");
        long startedNanos = System.nanoTime();
        String resultCategory = UNEXPECTED_EXCEPTION_RESULT_CATEGORY;
        try {
            AnswerVerificationResult result = Objects.requireNonNull(
                    verificationPort.verify(mode, context),
                    "answer verification port must return a result");
            resultCategory = verificationResultCategory(mode, result);
            return result;
        } catch (ExternalExecutionDeferredException exception) {
            resultCategory = "EXECUTION_DEFERRED";
            throw exception;
        } catch (AnswerVerificationUnavailableException exception) {
            resultCategory = "VERIFIER_UNAVAILABLE";
            throw exception;
        } catch (AnswerVerificationContractException exception) {
            resultCategory = CONTRACT_EXCEPTION_RESULT_CATEGORY;
            throw exception;
        } finally {
            logVerificationOperation(state, mode, resultCategory, startedNanos);
        }
    }

    private static String capabilityExecutionResultCategory(CapabilityExecutionResult result) {
        if (result instanceof CapabilityExecutionResult.Succeeded) {
            return SUCCEEDED_RESULT_CATEGORY;
        }
        return TYPED_FAILURE_RESULT_CATEGORY;
    }

    private static String repositoryRevisionResultCategory(RepositoryRevisionResult result) {
        if (result instanceof RepositoryRevisionResult.Ready) {
            return SUCCEEDED_RESULT_CATEGORY;
        }
        return TYPED_FAILURE_RESULT_CATEGORY;
    }

    private static void logLifecycleOperation(
            AgentRunState state,
            String operation,
            String resultCategory,
            long startedNanos) {
        Level level = SUCCEEDED_RESULT_CATEGORY.equals(resultCategory) ? Level.INFO : Level.WARNING;
        LOGGER.log(level,
                "agent lifecycle operation={0} runId={1} attemptId={2} resultCategory={3} elapsedMs={4}",
                new Object[]{
                        operation,
                        state.runId().value(),
                        state.currentAttempt().attemptId().value(),
                        resultCategory,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)});
    }

    private static String verificationResultCategory(
            AnswerVerificationMode mode,
            AnswerVerificationResult result) {
        if (mode == AnswerVerificationMode.LLM && result instanceof AnswerVerificationResult.LlmVerdict) {
            return "LLM_VERDICT";
        }
        if (mode == AnswerVerificationMode.CONTRACT_ONLY
                && result instanceof AnswerVerificationResult.ContractAccepted) {
            return "CONTRACT_ACCEPTED";
        }
        return INCOMPATIBLE_RESULT_CATEGORY;
    }

    private static void logVerificationOperation(
            AgentRunState state,
            AnswerVerificationMode mode,
            String resultCategory,
            long startedNanos) {
        Level level = "LLM_VERDICT".equals(resultCategory) || "CONTRACT_ACCEPTED".equals(resultCategory)
                ? Level.INFO : Level.WARNING;
        LOGGER.log(level,
                "answer verification operation=VERIFY runId={0} attemptId={1} mode={2} resultCategory={3} elapsedMs={4}",
                new Object[]{
                        state.runId().value(),
                        state.currentAttempt().attemptId().value(),
                        mode.name(),
                        resultCategory,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)});
    }
}
