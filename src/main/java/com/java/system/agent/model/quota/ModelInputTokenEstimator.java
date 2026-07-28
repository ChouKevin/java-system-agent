package com.java.system.agent.model.quota;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Objects;

/**
 * 在模型呼叫前以完整 prompt 內容估算 input token 數的本機估算器
 */
public final class ModelInputTokenEstimator {

    public long estimate(Prompt prompt) {
        Objects.requireNonNull(prompt, "model prompt must not be null");
        List<Message> messages = prompt.getInstructions();
        long codePoints = 0;
        for (Message message : messages) {
            String content = Objects.requireNonNull(message.getText(), "model prompt message content must not be null");
            codePoints = Math.addExact(codePoints, content.codePointCount(0, content.length()));
        }
        return Math.max(1L, (codePoints + 3L) / 4L);
    }
}
