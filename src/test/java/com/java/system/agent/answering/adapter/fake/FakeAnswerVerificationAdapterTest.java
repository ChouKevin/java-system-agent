package com.java.system.agent.answering.adapter.fake;

import com.java.system.agent.answering.domain.answer.AnswerDisposition;
import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.StatementId;
import com.java.system.agent.answering.domain.answer.StatementType;
import com.java.system.agent.answering.domain.answer.AnswerVerdict;
import com.java.system.agent.answering.domain.answer.AnswerVerificationMode;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.port.out.AnswerVerificationContext;
import com.java.system.agent.answering.port.out.AnswerVerificationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FakeAnswerVerificationAdapterTest {

    @Test
    void should_return_scripted_verdict_unchanged_and_retain_context() {
        AnswerVerdict verdict = new AnswerVerdict(AnswerDisposition.ACCEPTED_COMPLETE, List.of(), List.of(),
                List.of(), List.of());
        FakeAnswerVerificationAdapter adapter = new FakeAnswerVerificationAdapter(verdict);
        AnswerVerificationContext context = new AnswerVerificationContext("question", SessionHistory.empty(), document(),
                List.of(), List.of(), List.of(), List.of());

        assertThat(adapter.verify(AnswerVerificationMode.LLM, context))
                .isEqualTo(new AnswerVerificationResult.LlmVerdict(verdict));
        assertThat(adapter.contexts()).containsExactly(context);
    }

    private AnswerDocument document() {
        return new AnswerDocument(List.of(new AnswerStatement(
                new StatementId("statement-1"), StatementType.QUESTION, "answer", Optional.empty(), Set.of(), Set.of())));
    }
}
