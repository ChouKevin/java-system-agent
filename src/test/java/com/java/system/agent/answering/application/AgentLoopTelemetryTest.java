package com.java.system.agent.answering.application;

import com.java.system.agent.answering.domain.answer.AnswerVerificationMode;
import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.StatementId;
import com.java.system.agent.answering.domain.answer.StatementType;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.RunRequestIdentity;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.port.out.AnswerVerificationContext;
import com.java.system.agent.answering.port.out.AnswerVerificationResult;
import com.java.system.agent.answering.port.out.CapabilityCatalogPort;
import com.java.system.agent.answering.port.out.CapabilityExecutionContractException;
import com.java.system.agent.answering.port.out.CapabilityExecutionFailure;
import com.java.system.agent.answering.port.out.CapabilityExecutionFailureCode;
import com.java.system.agent.answering.port.out.CapabilityExecutionPort;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.answering.port.out.CapabilityInvocation;
import com.java.system.agent.answering.port.out.RepositoryCatalogPort;
import com.java.system.agent.answering.port.out.RepositoryDescriptor;
import com.java.system.agent.answering.port.out.RepositoryRevisionPort;
import com.java.system.agent.answering.port.out.RepositoryRevisionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AgentLoopTelemetry 外部結果分類與例外透明度測試
 */
class AgentLoopTelemetryTest {

    @Test
    void returnsSuccessfulCatalogAndRevisionResultsUnchanged() {
        List<CapabilityPolicy> capabilities = List.of();
        List<RepositoryDescriptor> repositories = List.of(new RepositoryDescriptor(new RepositoryId("repo-1"), "Repository"));
        RepositoryRevisionResult revision = RepositoryRevisionResult.ready(new RepositoryRevision("rev-1"));
        AgentLoopTelemetry telemetry = telemetry(invocation -> new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of()),
                (mode, context) -> new AnswerVerificationResult.ContractAccepted(), () -> capabilities, () -> repositories,
                repositoryId -> revision);

        assertThat(telemetry.loadCapabilities(state())).isEqualTo(capabilities);
        assertThat(telemetry.loadRepositories(state())).isEqualTo(repositories);
        assertThat(telemetry.resolveRevision(state(), new RepositoryId("repo-1"))).isSameAs(revision);
    }

    @Test
    void returnsTypedCapabilityFailureUnchanged() {
        CapabilityExecutionResult result = new CapabilityExecutionResult.Failed(new CapabilityExecutionFailure(
                CapabilityExecutionFailureCode.DEPENDENCY_FAILURE, "dependency failed", "semantic-service"));
        AgentLoopTelemetry telemetry = telemetry(invocation -> result,
                (mode, context) -> new AnswerVerificationResult.ContractAccepted(), List::of, List::of,
                repositoryId -> RepositoryRevisionResult.ready(new RepositoryRevision("rev-1")));

        assertThat(telemetry.executeCapability(state(), null)).isSameAs(result);
    }

    @Test
    void propagatesCapabilityContractExceptionUnchanged() {
        CapabilityExecutionContractException failure = new CapabilityExecutionContractException("contract failure");
        AgentLoopTelemetry telemetry = telemetry(invocation -> { throw failure; },
                (mode, context) -> new AnswerVerificationResult.ContractAccepted(), List::of, List::of,
                repositoryId -> RepositoryRevisionResult.ready(new RepositoryRevision("rev-1")));

        assertThatThrownBy(() -> telemetry.executeCapability(state(), null)).isSameAs(failure);
    }

    @Test
    void propagatesUnexpectedVerifierExceptionUnchanged() {
        IllegalStateException failure = new IllegalStateException("unexpected failure");
        AgentLoopTelemetry telemetry = telemetry(invocation -> new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of()),
                (mode, context) -> { throw failure; }, List::of, List::of,
                repositoryId -> RepositoryRevisionResult.ready(new RepositoryRevision("rev-1")));

        assertThatThrownBy(() -> telemetry.verifyAnswer(state(), AnswerVerificationMode.CONTRACT_ONLY, verificationContext()))
                .isSameAs(failure);
    }

    @Test
    void returnsIncompatibleVerifierResultUnchanged() {
        AnswerVerificationResult result = new AnswerVerificationResult.ContractAccepted();
        AgentLoopTelemetry telemetry = telemetry(invocation -> new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of()),
                (mode, context) -> result, List::of, List::of,
                repositoryId -> RepositoryRevisionResult.ready(new RepositoryRevision("rev-1")));

        assertThat(telemetry.verifyAnswer(state(), AnswerVerificationMode.LLM, verificationContext())).isSameAs(result);
    }

    @Test
    void classifiesNullExternalResultsAsContractFailuresWithoutReplacingTheException() {
        AgentLoopTelemetry telemetry = telemetry(invocation -> null, (mode, context) -> null,
                () -> null, () -> null, repositoryId -> null);

        assertThatThrownBy(() -> telemetry.loadCapabilities(state())).isInstanceOf(NullPointerException.class)
                .hasMessage("capability catalog port must return a catalog");
        assertThatThrownBy(() -> telemetry.loadRepositories(state())).isInstanceOf(NullPointerException.class)
                .hasMessage("repository catalog port must return a catalog");
        assertThatThrownBy(() -> telemetry.resolveRevision(state(), new RepositoryId("repo-1")))
                .isInstanceOf(NullPointerException.class).hasMessage("repository revision port must return a result");
        assertThatThrownBy(() -> telemetry.executeCapability(state(), null)).isInstanceOf(NullPointerException.class)
                .hasMessage("capability execution port must return a result");
        assertThatThrownBy(() -> telemetry.verifyAnswer(state(), AnswerVerificationMode.CONTRACT_ONLY, verificationContext()))
                .isInstanceOf(NullPointerException.class).hasMessage("answer verification port must return a result");
    }

    private AgentLoopTelemetry telemetry(
            CapabilityExecutionPort execution,
            com.java.system.agent.answering.port.out.AnswerVerificationPort verification,
            CapabilityCatalogPort capabilities,
            RepositoryCatalogPort repositories,
            RepositoryRevisionPort revisions) {
        return new AgentLoopTelemetry(execution, verification, capabilities, repositories, revisions);
    }

    private AgentRunState state() {
        return AgentRunState.initial(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"),
                new AttemptBudget(3, 0, 2, 0, 2, 0, 1, 0),
                new RunRequestIdentity("session-1", new ParticipantRef("test", "participant-1"), "Question?"));
    }

    private AnswerVerificationContext verificationContext() {
        AnswerDocument document = new AnswerDocument(List.of(new AnswerStatement(
                new StatementId("statement-1"), StatementType.QUESTION, "Answer", Optional.empty(), Set.of(), Set.of())));
        return new AnswerVerificationContext("Question?", SessionHistory.empty(), document,
                List.of(), List.of(), List.of(), List.of());
    }
}
