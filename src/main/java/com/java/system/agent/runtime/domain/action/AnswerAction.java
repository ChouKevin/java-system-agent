package com.java.system.agent.runtime.domain.action;

import com.java.system.agent.runtime.domain.answer.AnswerDocument;

import java.util.Objects;

/**
 * 提交一份待驗證回答文件的動作
 */
public record AnswerAction(AnswerDocument document) implements AgentAction {
    public AnswerAction { Objects.requireNonNull(document, "answer action document must not be null"); }
}
