package com.java.system.agent.model.quota;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelInputTokenEstimatorTest {

    @Test
    void estimatesEveryMessageByUnicodeCodePointAndRoundsUp() {
        Prompt prompt = new Prompt(List.of(new SystemMessage("ab😀"), new UserMessage("cd")));

        long estimatedTokens = new ModelInputTokenEstimator().estimate(prompt);

        assertThat(estimatedTokens).isEqualTo(2);
    }

    @Test
    void returnsOneForAnEmptyPrompt() {
        long estimatedTokens = new ModelInputTokenEstimator().estimate(new Prompt(""));

        assertThat(estimatedTokens).isEqualTo(1);
    }
}
