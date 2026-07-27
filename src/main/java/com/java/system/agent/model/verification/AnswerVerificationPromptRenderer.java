package com.java.system.agent.model.verification;

import com.java.system.agent.runtime.domain.answer.AnswerStatement;
import com.java.system.agent.runtime.domain.conversation.ConversationTurn;
import com.java.system.agent.runtime.domain.evidence.IssuedEvidence;
import com.java.system.agent.runtime.domain.observation.AgentObservation;
import com.java.system.agent.runtime.port.out.AnswerVerificationContext;

import java.util.Objects;

/**
 * 將 verifier 可用與精確引用 context 穩定轉為單次模型提示
 */
public final class AnswerVerificationPromptRenderer {

    public static final String SYSTEM_INSTRUCTION = """
            Evaluate the proposed answer only against the supplied context.
            Return one complete, inconclusive, or rejected disposition and a judgment for every statement.
            Do not rewrite the proposed answer.
            """;

    /**
     * 只輸出 verifier contract 允許的資料且保留既有集合順序
     */
    public String render(AnswerVerificationContext context, String responseContract) {
        Objects.requireNonNull(context, "answer verification context must not be null");
        Objects.requireNonNull(responseContract, "answer verification response contract must not be null");
        StringBuilder prompt = new StringBuilder();
        section(prompt, "Current question", context.question());
        prompt.append("Session history:\n");
        for (ConversationTurn turn : context.sessionHistory().turns()) {
            prompt.append("- user: ").append(turn.userMessage()).append('\n');
            prompt.append("  assistant: ").append(turn.assistantMessage()).append('\n');
        }
        prompt.append("Proposed document:\n");
        for (AnswerStatement statement : context.document().statements()) {
            prompt.append("- ").append(statement.statementId().value()).append(" [").append(statement.type())
                    .append("]: ").append(statement.text()).append('\n');
        }
        evidenceSection(prompt, "Available evidence", context.availableEvidence());
        observationSection(prompt, "Available observations", context.availableObservations());
        evidenceSection(prompt, "Cited evidence", context.citedEvidence());
        observationSection(prompt, "Referenced observations", context.referencedObservations());
        section(prompt, "Response contract", responseContract);
        return prompt.toString();
    }

    private static void evidenceSection(StringBuilder prompt, String label, Iterable<IssuedEvidence> evidence) {
        prompt.append(label).append(":\n");
        for (IssuedEvidence item : evidence) {
            prompt.append("- ").append(item.handle().value()).append(": ").append(item.evidence().content()).append('\n');
        }
    }

    private static void observationSection(StringBuilder prompt, String label, Iterable<AgentObservation> observations) {
        prompt.append(label).append(":\n");
        for (AgentObservation item : observations) {
            prompt.append("- ").append(item.id().value()).append(": ").append(item.description()).append('\n');
        }
    }

    private static void section(StringBuilder prompt, String label, String content) {
        prompt.append(label).append(":\n").append(content).append('\n');
    }
}
