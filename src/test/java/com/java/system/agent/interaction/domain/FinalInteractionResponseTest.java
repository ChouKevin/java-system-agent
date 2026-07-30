package com.java.system.agent.interaction.domain;

import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.run.RunResponseKind;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FinalInteractionResponse 的最終回應文字不變條件測試
 */
class FinalInteractionResponseTest {

    @Test
    void rejectsWhitespaceOnlyResponseText() {
        assertThatThrownBy(() -> new FinalInteractionResponse(
                new AnalysisRunId("run-1"),
                RunOutcome.COMPLETED,
                RunResponseKind.ANSWER,
                " \t\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
