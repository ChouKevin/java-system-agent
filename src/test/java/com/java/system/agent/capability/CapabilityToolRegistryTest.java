package com.java.system.agent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.capability.tool.CapabilityToolRegistry;
import com.java.system.agent.runtime.domain.capability.CapabilityPolicy;
import com.java.system.agent.runtime.domain.candidate.CandidateKind;
import com.java.system.agent.runtime.domain.conversation.SessionHistory;
import com.java.system.agent.runtime.domain.handle.CapabilityHandle;
import com.java.system.agent.runtime.domain.handle.HandleBinding;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import com.java.system.agent.runtime.application.validation.ActionRejectionCode;
import com.java.system.agent.runtime.application.validation.ActionValidation;
import com.java.system.agent.runtime.application.validation.AgentActionValidator;
import com.java.system.agent.runtime.application.validation.AgentValidationContext;
import com.java.system.agent.runtime.domain.action.QueryAction;
import com.java.system.agent.runtime.port.out.AgentActionProposal;
import com.java.system.agent.runtime.port.out.AgentPromptContext;
import com.java.system.agent.runtime.port.out.CapabilityExecutionResult;
import com.java.system.agent.runtime.port.out.CapabilityInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Capability tool registry 的 policy 與嚴格 provider input 邊界測試
 */
class CapabilityToolRegistryTest {

    @Test
    void exposesPolicyAndRejectsUnknownOrBlankToolInputWithoutCallingExecutor() throws Exception {
        CapabilityPolicy policy = new CapabilityPolicy("codebase.lookup-api-route", "v1",
                Set.of(CandidateKind.REPOSITORY), 0, 1);
        CapabilityToolRegistry.ToolInputDecoder decoder = CapabilityToolRegistry.decoder(
                CapabilityToolRegistry.requiredText("apiPath"), CapabilityToolRegistry.optionalText("httpMethod"));
        CapabilityToolRegistry.Registration registration = CapabilityToolRegistry.registration(
                policy, decoder, new NoopExecutor(policy));
        CapabilityToolRegistry registry = new CapabilityToolRegistry(List.of(registration));

        assertThat(registry.availableCapabilities()).containsExactly(policy);
        assertThat(new ObjectMapper().readTree(registration.callback().getToolDefinition().inputSchema())
                .path("additionalProperties").asBoolean()).isFalse();
        assertThat(decoder.decode("""
                {"candidateHandles":[],"questionToResolve":"Find route","rationale":"Need route","apiPath":"/orders"}
                """, new ObjectMapper()).arguments()).containsExactlyEntriesOf(Map.of("apiPath", "/orders"));
        assertThatThrownBy(() -> decoder.decode("""
                {"candidateHandles":[],"questionToResolve":"Find route","rationale":"Need route","apiPath":" ","extra":"x"}
                """, new ObjectMapper())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decoder.decode("""
                {"candidateHandles":[],"questionToResolve":"Find route","rationale":"Need route","apiPath":" "}
                """, new ObjectMapper())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void strictlyValidatesIntegerEnumAndRequiredToolArgumentsWhileAcceptingEmptyCandidates() {
        CapabilityToolRegistry.ToolInputDecoder decoder = CapabilityToolRegistry.decoder(
                CapabilityToolRegistry.optionalEnum("type", Set.of("API", "MQ", "SCHEDULE")),
                CapabilityToolRegistry.requiredInteger("limit", 1, 20));
        ObjectMapper objectMapper = new ObjectMapper();

        assertThat(decoder.decode("""
                {"candidateHandles":[],"questionToResolve":"Find routes","rationale":"Need options","type":"API","limit":1}
                """, objectMapper).candidateHandles()).isEmpty();
        assertThatThrownBy(() -> decoder.decode("""
                {"candidateHandles":[],"questionToResolve":"Find routes","rationale":"Need options","type":"API","limit":4294967297}
                """, objectMapper)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decoder.decode("""
                {"candidateHandles":[],"questionToResolve":"Find routes","rationale":"Need options","type":"API","limit":21}
                """, objectMapper)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decoder.decode("""
                {"candidateHandles":[],"questionToResolve":"Find routes","rationale":"Need options","type":"API","limit":1.5}
                """, objectMapper)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decoder.decode("""
                {"candidateHandles":[],"questionToResolve":"Find routes","rationale":"Need options","type":"GRAPH","limit":1}
                """, objectMapper)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decoder.decode("""
                {"candidateHandles":[],"questionToResolve":"Find routes","rationale":"Need options","type":"API"}
                """, objectMapper)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesUnmatchedNonblankCandidateForRuntimeUnknownCandidateValidation() {
        CapabilityPolicy policy = new CapabilityPolicy("callers", "v1", Set.of(CandidateKind.REPOSITORY), 0, 1);
        CapabilityToolRegistry registry = new CapabilityToolRegistry(List.of(CapabilityToolRegistry.registration(
                policy, CapabilityToolRegistry.decoder(), new NoopExecutor(policy))));
        HandleBinding binding = binding();
        CapabilityHandle capability = new CapabilityHandle("capability-1", binding);
        AgentPromptContext promptContext = new AgentPromptContext("Find callers", SessionHistory.empty(),
                binding.runId(), binding.attemptId(), Map.of(capability, policy), Map.of(), Map.of(), Map.of(), Optional.empty(),
                budget(), false);

        AgentActionProposal proposal = registry.interpretToolCall(new AssistantMessage.ToolCall("call-1", "function", "callers", """
                {"candidateHandles":["unissued-candidate"],"questionToResolve":"Find callers","rationale":"Trace callers"}
                """), promptContext);

        assertThat(proposal).isInstanceOf(AgentActionProposal.Proposed.class);
        QueryAction action = (QueryAction) ((AgentActionProposal.Proposed) proposal).action();
        AgentValidationContext validationContext = new AgentValidationContext(Map.of(capability, policy), Map.of(), Map.of(), Map.of(),
                binding, budget(), false);
        ActionValidation validation = new AgentActionValidator().validate(action, validationContext);
        assertThat(validation).isInstanceOf(ActionValidation.Rejected.class);
        assertThat(((ActionValidation.Rejected) validation).code()).isEqualTo(ActionRejectionCode.UNKNOWN_CANDIDATE);
    }

    private static HandleBinding binding() {
        RevisionVector revisions = RevisionVector.empty().pin(new RepositoryId("repo-1"), new RepositoryRevision("rev-1"));
        return new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"), revisions);
    }

    private static AttemptBudget budget() {
        return new AttemptBudget(3, 0, 3, 0, 3, 0, 1, 0, 1, 0);
    }

    private record NoopExecutor(CapabilityPolicy capability) implements CapabilityExecutor {
        @Override
        public CapabilityExecutionResult execute(CapabilityInvocation invocation) {
            return new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of());
        }
    }
}
