package com.java.system.agent.answering.domain.action;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 模型唯一可以提出的下一步動作
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "action_type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = QueryAction.class, name = "QUERY"),
        @JsonSubTypes.Type(value = AnswerAction.class, name = "ANSWER"),
        @JsonSubTypes.Type(value = ClarifyAction.class, name = "CLARIFY")
})
public sealed interface AgentAction permits QueryAction, AnswerAction, ClarifyAction {
}
