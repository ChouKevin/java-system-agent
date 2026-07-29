package com.java.system.agent.answering.port.in;

import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerVerificationBasis;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.StatementId;
import com.java.system.agent.answering.domain.answer.StatementType;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.run.RunResponseKind;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * AnswerQuestionResult 對 terminal outcome 與回答文件的一致性邊界測試
 */
class AnswerQuestionResultTest {

    @Test
    void enforcesOutcomeAndAnswerDocumentCoherence() {
        assertThatCode(() -> result(RunOutcome.COMPLETED, Optional.of(document()))).doesNotThrowAnyException();
        assertThatCode(() -> result(RunOutcome.FAILED, Optional.empty())).doesNotThrowAnyException();
        assertThatCode(() -> result(RunOutcome.CANCELLED, Optional.empty())).doesNotThrowAnyException();
        assertThatCode(() -> result(RunOutcome.INCONCLUSIVE, Optional.empty())).doesNotThrowAnyException();
        assertThatCode(() -> result(RunOutcome.INCONCLUSIVE, Optional.of(document()))).doesNotThrowAnyException();

        assertThatIllegalArgumentException().isThrownBy(() -> result(RunOutcome.COMPLETED, Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> result(RunOutcome.FAILED, Optional.of(document())));
        assertThatIllegalArgumentException().isThrownBy(() -> result(RunOutcome.CANCELLED, Optional.of(document())));
        assertThatIllegalArgumentException().isThrownBy(() -> new AnswerQuestionResult(
                new AnalysisRunId("run-1"), RunOutcome.INCONCLUSIVE, "different", Optional.of(document()),
                RunResponseKind.ANSWER, Optional.of(AnswerVerificationBasis.LLM), RevisionVector.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> new AnswerQuestionResult(
                new AnalysisRunId("run-1"), RunOutcome.INCONCLUSIVE, document().renderParagraphs(), Optional.of(document()),
                RunResponseKind.ANSWER, Optional.of(AnswerVerificationBasis.CONTRACT_ONLY), RevisionVector.empty()));
    }

    private static AnswerQuestionResult result(RunOutcome outcome, Optional<AnswerDocument> answerDocument) {
        String response = answerDocument.map(AnswerDocument::renderParagraphs).orElse("response");
        RunResponseKind kind = answerDocument.isPresent()
                ? RunResponseKind.ANSWER
                : RunResponseKind.RUNTIME_NOTICE;
        Optional<AnswerVerificationBasis> basis = answerDocument.isPresent()
                ? Optional.of(AnswerVerificationBasis.LLM)
                : Optional.empty();
        return new AnswerQuestionResult(new AnalysisRunId("run-1"), outcome, response, answerDocument, kind, basis,
                RevisionVector.empty());
    }

    private static AnswerDocument document() {
        return new AnswerDocument(List.of(new AnswerStatement(
                new StatementId("statement-1"), StatementType.QUESTION, "answer", Optional.empty(), Set.of(), Set.of())));
    }
}
