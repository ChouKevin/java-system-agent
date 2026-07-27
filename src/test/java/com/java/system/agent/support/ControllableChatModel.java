package com.java.system.agent.support;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;

/**
 * 在 production Spring AI 邊界以 FIFO JSON 回應驅動整合測試的 ChatModel
 */
public final class ControllableChatModel implements ChatModel {

    private final Queue<String> responses = new ArrayDeque<>();
    private final List<Prompt> prompts = new ArrayList<>();

    public synchronized void enqueue(String response) {
        Objects.requireNonNull(response, "chat response must not be null");
        if (response.isBlank()) {
            throw new IllegalArgumentException("chat response must not be blank");
        }
        responses.add(response);
    }

    public synchronized List<Prompt> prompts() {
        return List.copyOf(prompts);
    }

    @Override
    public synchronized ChatResponse call(Prompt prompt) {
        Objects.requireNonNull(prompt, "chat prompt must not be null");
        if (responses.isEmpty()) {
            throw new IllegalStateException("controllable chat model response queue is empty");
        }
        prompts.add(prompt);
        String response = responses.remove();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
    }
}
