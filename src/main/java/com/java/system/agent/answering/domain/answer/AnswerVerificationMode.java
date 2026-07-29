package com.java.system.agent.answering.domain.answer;

/**
 * 回答驗證在持久化 checkpoint 後採用的模式
 */
public enum AnswerVerificationMode {
    LLM,
    CONTRACT_ONLY
}
