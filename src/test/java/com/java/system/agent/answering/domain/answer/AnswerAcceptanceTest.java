package com.java.system.agent.answering.domain.answer;

import com.java.system.agent.answering.domain.run.RunOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * AnswerAcceptance 將驗證依據與唯一終態映射固定在持久化契約的測試
 */
class AnswerAcceptanceTest {

    @Test
    void llmCompleteMapsToCompleted() {
        AnswerAcceptance acceptance = AnswerAcceptance.llm(accepted(AnswerDisposition.ACCEPTED_COMPLETE));

        assertThat(acceptance.expectedOutcome()).isEqualTo(RunOutcome.COMPLETED);
        assertThat(acceptance.verificationBasis()).isEqualTo(AnswerVerificationBasis.LLM);
    }

    @Test
    void contractOnlyDoesNotCarryVerdictAndMapsToCompleted() {
        AnswerAcceptance acceptance = AnswerAcceptance.contractOnly();

        assertThat(acceptance.expectedOutcome()).isEqualTo(RunOutcome.COMPLETED);
        assertThat(acceptance.verdict()).isEmpty();
    }

    @Test
    void llmRejectVerdictIsNotAnAcceptance() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> AnswerAcceptance.llm(accepted(AnswerDisposition.REJECTED)));
    }

    private static AnswerVerdict accepted(AnswerDisposition disposition) {
        return new AnswerVerdict(disposition, List.of(), List.of(), List.of(), List.of("verified"));
    }
}
