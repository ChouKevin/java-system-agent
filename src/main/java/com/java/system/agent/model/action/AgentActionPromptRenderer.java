package com.java.system.agent.model.action;

import com.java.system.agent.runtime.domain.capability.CapabilityPolicy;
import com.java.system.agent.runtime.domain.candidate.IssuedCandidate;
import com.java.system.agent.runtime.domain.conversation.ConversationTurn;
import com.java.system.agent.runtime.domain.evidence.IssuedEvidence;
import com.java.system.agent.runtime.domain.handle.CapabilityHandle;
import com.java.system.agent.runtime.domain.handle.CandidateHandle;
import com.java.system.agent.runtime.domain.handle.EvidenceHandle;
import com.java.system.agent.runtime.domain.observation.AgentObservation;
import com.java.system.agent.runtime.domain.observation.ObservationId;
import com.java.system.agent.runtime.port.out.AgentPromptContext;

import java.util.Map;
import java.util.Objects;

/**
 * 將 runtime 已發行 action context 穩定轉為單次模型提示
 */
public final class AgentActionPromptRenderer {

    public static final String SYSTEM_INSTRUCTION = """
            Choose exactly one registered tool call for QUERY, or one textual ANSWER or CLARIFY response, never both.
            Use only issued opaque handles.
            Preserve the candidate subset and order you intend.
            Express unresolved uncertainty in answer statements, observations, or clarification.
            Do not emit confidence, score, rank, adapter name, URL, or retry instruction.
            """;

    /**
     * 依 runtime collection 的既有順序輸出明確 action context
     */
    public String render(AgentPromptContext context, String responseContract) {
        Objects.requireNonNull(context, "agent prompt context must not be null");
        Objects.requireNonNull(responseContract, "action response contract must not be null");
        StringBuilder prompt = new StringBuilder();
        section(prompt, "Original question", context.originalQuestion());
        prompt.append("Session turns:\n");
        for (ConversationTurn turn : context.sessionHistory().turns()) {
            prompt.append(turn.participant().promptLabel()).append(": ").append(turn.userMessage()).append('\n');
            prompt.append("assistant: ").append(turn.assistantMessage()).append('\n');
        }
        prompt.append("Capabilities:\n");
        for (Map.Entry<CapabilityHandle, CapabilityPolicy> entry : context.issuedCapabilities().entrySet()) {
            CapabilityPolicy descriptor = entry.getValue();
            prompt.append("- ").append(entry.getKey().value()).append(": ").append(descriptor.name())
                    .append("@ ").append(descriptor.version()).append('\n');
        }
        prompt.append("Candidates:\n");
        for (Map.Entry<CandidateHandle, IssuedCandidate> entry : context.issuedCandidates().entrySet()) {
            prompt.append("- ").append(entry.getKey().value()).append(": ")
                    .append(entry.getValue().candidate().description()).append('\n');
        }
        prompt.append("Evidence:\n");
        for (Map.Entry<EvidenceHandle, IssuedEvidence> entry : context.issuedEvidence().entrySet()) {
            prompt.append("- ").append(entry.getKey().value()).append(": ")
                    .append(entry.getValue().evidence().content()).append('\n');
        }
        prompt.append("Observations:\n");
        for (Map.Entry<ObservationId, AgentObservation> entry : context.observations().entrySet()) {
            prompt.append("- ").append(entry.getKey().value()).append(": ")
                    .append(entry.getValue().description()).append('\n');
        }
        section(prompt, "Latest rejection", context.latestRejection().orElse("none"));
        section(prompt, "Remaining budget", remainingBudget(context));
        section(prompt, "Response contract", responseContract);
        return prompt.toString();
    }

    private static void section(StringBuilder prompt, String label, String content) {
        prompt.append(label).append(":\n").append(content).append('\n');
    }

    private static String remainingBudget(AgentPromptContext context) {
        return "agentSteps=" + (context.budget().maxAgentSteps() - context.budget().usedAgentSteps())
                + ", queryExecutions=" + (context.budget().maxQueryExecutions() - context.budget().usedQueryExecutions())
                + ", actionRejections=" + (context.budget().maxActionRejections() - context.budget().usedActionRejections());
    }
}
