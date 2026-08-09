package com.java.system.agent.answering.domain.run;

import com.java.system.agent.answering.domain.action.AgentAction;

import java.util.Objects;

/**
 * 一次模型選擇或其可確認結果的 run 級不可變歷史項目
 */
public sealed interface ModelInteraction permits ModelInteraction.ActionSelected,
        ModelInteraction.ActionResultRecorded, ModelInteraction.MalformedResponse {

    /**
     * 已解析並選定的模型動作
     */
    record ActionSelected(AnalysisAttemptId attemptId, AgentAction action) implements ModelInteraction {

        public ActionSelected {
            Objects.requireNonNull(attemptId, "selected action attempt ID must not be null");
            Objects.requireNonNull(action, "selected agent action must not be null");
        }
    }

    /**
     * 已確認可歸屬於模型動作的結果
     */
    record ActionResultRecorded(AnalysisAttemptId attemptId, ActionResult result) implements ModelInteraction {

        public ActionResultRecorded {
            Objects.requireNonNull(attemptId, "action result attempt ID must not be null");
            Objects.requireNonNull(result, "action result must not be null");
        }
    }

    /**
     * 無法解析成具型別動作的模型回應
     */
    record MalformedResponse(AnalysisAttemptId attemptId, String description) implements ModelInteraction {

        public MalformedResponse {
            Objects.requireNonNull(attemptId, "malformed response attempt ID must not be null");
            Objects.requireNonNull(description, "malformed response description must not be null");
            if (description.isBlank()) {
                throw new IllegalArgumentException("malformed response description must not be blank");
            }
        }
    }
}
