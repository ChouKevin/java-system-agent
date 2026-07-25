package com.java.system.agent.runtime.domain.conversation;

import java.util.Objects;
import java.util.Optional;

/**
 * 一輪對話：使用者的問題、Agent 的回答摘要，以及是否曾要求釐清
 *
 * <p>{@code clarificationAsked} 有值時代表這一輪 Agent 沒有給出最終答案，
 * 而是回問使用者；{@link ConversationContext#hasPendingClarification()} 依此判斷
 * 是否還在等待使用者回覆釐清問題</p>
 */
public record ConversationTurn(
        String question,
        String answerSummary,
        Optional<String> clarificationAsked) {

    public ConversationTurn {
        Objects.requireNonNull(question, "question must not be null");
        Objects.requireNonNull(answerSummary, "answer summary must not be null");
        Objects.requireNonNull(clarificationAsked, "clarification asked must not be null");
    }
}
