package com.java.system.agent.ai.loop.verify;

import com.java.system.agent.ai.loop.Candidate;
import com.java.system.agent.ai.loop.LoopRequest;
import com.java.system.agent.ai.loop.LoopState;
import com.java.system.agent.ai.loop.Verdict;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TranslatorVerifyGateTest {

    @Test
    void revisesUncertainGraphWhenAnswerDoesNotAcknowledgeUncertainty() {
        String callGraphJson = """
                {"data":{"edges":[{"resolutionStrategy":"UNRESOLVED","confidence":"LOW"}]}}
                """;

        Verdict verdict = new TranslatorVerifyGate(callGraphJson)
                .verify(new Candidate("系統會完成所有檢核"), state());

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.critique()).contains("未解析或低信心");
    }

    @Test
    void acceptsUncertainGraphWhenAnswerAcknowledgesUncertainty() {
        String callGraphJson = """
                {"data":{"edges":[{"resolutionStrategy":"UNRESOLVED"}]}}
                """;

        Verdict verdict = new TranslatorVerifyGate(callGraphJson)
                .verify(new Candidate("系統可能還需要人工確認部分分支"), state());

        assertThat(verdict.accepted()).isTrue();
    }

    private LoopState state() {
        return LoopState.init(new LoopRequest("t", "q"));
    }
}
