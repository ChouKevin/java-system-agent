package com.java.system.agent.runtime.application;

import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.answer.AnswerStatement;
import com.java.system.agent.runtime.domain.answer.StatementId;
import com.java.system.agent.runtime.domain.answer.StatementType;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * AgentLoopResult 對 terminal outcome 與回答文件的一致性邊界測試
 */
class AgentLoopResultTest {

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
        assertThatIllegalArgumentException().isThrownBy(() -> new AgentLoopResult(
                new AnalysisRunId("run-1"), RunOutcome.INCONCLUSIVE, "different", Optional.of(document()),
                RevisionVector.empty()));
    }

    private static AgentLoopResult result(RunOutcome outcome, Optional<AnswerDocument> answerDocument) {
        String response = answerDocument.map(AnswerDocument::renderParagraphs).orElse("response");
        return new AgentLoopResult(new AnalysisRunId("run-1"), outcome, response, answerDocument, RevisionVector.empty());
    }

    private static AnswerDocument document() {
        return new AnswerDocument(List.of(new AnswerStatement(
                new StatementId("statement-1"), StatementType.QUESTION, "answer", Optional.empty(), Set.of(), Set.of())));
    }
}
