package com.java.system.agent.runtime.adapter.fake;

import com.java.system.agent.runtime.domain.answer.AnswerDisposition;
import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.answer.AnswerStatement;
import com.java.system.agent.runtime.domain.answer.StatementId;
import com.java.system.agent.runtime.domain.answer.StatementType;
import com.java.system.agent.runtime.domain.answer.AnswerVerdict;
import com.java.system.agent.runtime.port.out.AnswerVerificationContext;
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
        AnswerVerificationContext context = new AnswerVerificationContext("question", document(),
                List.of(), List.of());

        assertThat(adapter.verify(context)).isSameAs(verdict);
        assertThat(adapter.contexts()).containsExactly(context);
    }

    private AnswerDocument document() {
        return new AnswerDocument(List.of(new AnswerStatement(
                new StatementId("statement-1"), StatementType.QUESTION, "answer", Optional.empty(), Set.of(), Set.of())));
    }
}
