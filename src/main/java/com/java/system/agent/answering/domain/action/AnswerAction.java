package com.java.system.agent.answering.domain.action;

import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.plan.NeedResolution;

import java.util.List;
import java.util.Objects;

/**
 * 提交一份待驗證回答文件的動作
 */
public record AnswerAction(AnswerDocument document, List<NeedResolution> resolutions) implements AgentAction {
    public AnswerAction {
        Objects.requireNonNull(document, "answer action document must not be null");
        Objects.requireNonNull(resolutions, "answer action resolutions must not be null");
        resolutions = List.copyOf(resolutions);
    }
}
