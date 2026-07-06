package com.java.system.agent.ai.loop.verify;

import com.java.system.agent.ai.loop.Candidate;
import com.java.system.agent.ai.loop.LoopRequest;
import com.java.system.agent.ai.loop.LoopState;
import com.java.system.agent.ai.loop.Verdict;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmVerifierTest {

    private static LoopState state() {
        return LoopState.init(new LoopRequest("t", "q"));
    }

    @Test
    void parsesPassAsAccept() {
        ChatModel model = new FakeChatModel("好的，檢查完成。\nVERDICT: PASS");

        Verdict verdict = new LlmVerifier(model, "%1$s %2$s", "self-eval")
                .verify(new Candidate("好答案"), state());

        assertThat(verdict.accepted()).isTrue();
    }

    @Test
    void parsesReviseWithReason() {
        ChatModel model = new FakeChatModel("分析如下...\nVERDICT: REVISE — 缺少進入點依據");

        Verdict verdict = new LlmVerifier(model, "%1$s %2$s", "critic")
                .verify(new Candidate("薄弱"), state());

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.critique()).contains("缺少進入點依據");
    }

    @Test
    void missingMarkerFailsOpen() {
        ChatModel model = new FakeChatModel("看起來可以，但沒有 marker");

        Verdict verdict = new LlmVerifier(model, "%1$s %2$s", "critic")
                .verify(new Candidate("答案"), state());

        assertThat(verdict.accepted()).isTrue();
    }

    private static final class FakeChatModel implements ChatModel {

        private final String responseText;

        private FakeChatModel(String responseText) {
            this.responseText = responseText;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(responseText))));
        }
    }
}
