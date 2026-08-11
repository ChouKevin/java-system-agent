package com.java.system.agent.answering.domain.run;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.action.AnswerAction;
import com.java.system.agent.answering.domain.action.ClarifyAction;
import com.java.system.agent.answering.domain.action.ExecuteAction;
import com.java.system.agent.answering.domain.action.PlanAction;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.answer.AnswerVerdict;
import com.java.system.agent.answering.domain.plan.QuestionPlan;

import java.util.List;
import java.util.Objects;

/**
 * 模型動作在受信任邊界已知的結果摘要
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "result_type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ActionResult.QuerySucceeded.class, name = "QUERY_SUCCEEDED"),
        @JsonSubTypes.Type(value = ActionResult.QueryFailed.class, name = "QUERY_FAILED"),
        @JsonSubTypes.Type(value = ActionResult.QueryInvalidated.class, name = "QUERY_INVALIDATED"),
        @JsonSubTypes.Type(value = ActionResult.ExecuteCompleted.class, name = "EXECUTE_COMPLETED"),
        @JsonSubTypes.Type(value = ActionResult.ValidationRejected.class, name = "VALIDATION_REJECTED"),
        @JsonSubTypes.Type(value = ActionResult.ActionInterrupted.class, name = "ACTION_INTERRUPTED"),
        @JsonSubTypes.Type(value = ActionResult.AnswerRejected.class, name = "ANSWER_REJECTED"),
        @JsonSubTypes.Type(value = ActionResult.AnswerAccepted.class, name = "ANSWER_ACCEPTED"),
        @JsonSubTypes.Type(value = ActionResult.ClarificationAccepted.class, name = "CLARIFICATION_ACCEPTED"),
        @JsonSubTypes.Type(value = ActionResult.QuestionPlanRecorded.class, name = "QUESTION_PLAN_RECORDED")
})
public sealed interface ActionResult permits ActionResult.QuerySucceeded, ActionResult.QueryFailed,
        ActionResult.QueryInvalidated, ActionResult.ExecuteCompleted, ActionResult.ValidationRejected,
        ActionResult.ActionInterrupted, ActionResult.AnswerRejected, ActionResult.AnswerAccepted,
        ActionResult.ClarificationAccepted, ActionResult.QuestionPlanRecorded {

    /**
     * QUERY 動作成功後產生的穩定參考值
     */
    record QuerySucceeded(List<String> candidateHandleValues, List<String> evidenceHandleValues,
                          List<String> observationIds) implements ActionResult {

        public QuerySucceeded {
            candidateHandleValues = stableValues(candidateHandleValues, "query result candidate handles");
            evidenceHandleValues = stableValues(evidenceHandleValues, "query result evidence handles");
            observationIds = stableValues(observationIds, "query result observation IDs");
        }
    }

    /**
     * QUERY 動作失敗後已保存的觀察與說明
     */
    record QueryFailed(List<String> observationIds, String description) implements ActionResult {

        public QueryFailed {
            observationIds = stableValues(observationIds, "failed query observation IDs");
            requireText(description, "failed query description");
        }
    }

    /**
     * QUERY 動作因 revision 失效而未完成
     */
    record QueryInvalidated(String description) implements ActionResult {

        public QueryInvalidated {
            requireText(description, "invalidated query description");
        }
    }

    /**
     * EXECUTE 動作完成後的結果摘要
     */
    record ExecuteCompleted(ExecuteOutcome outcome, List<String> observationIds, String description) implements ActionResult {

        public ExecuteCompleted {
            Objects.requireNonNull(outcome, "execute outcome must not be null");
            observationIds = stableValues(observationIds, "execute observation IDs");
            requireText(description, "execute description");
        }
    }

    /**
     * EXECUTE 動作完成後可持久化的封閉結果
     */
    enum ExecuteOutcome {
        NOT_IMPLEMENTED
    }

    /**
     * 模型動作未通過驗證的穩定代碼與說明
     */
    record ValidationRejected(String code, String description) implements ActionResult {

        public ValidationRejected {
            requireText(code, "action rejection code");
            requireText(description, "action rejection description");
        }
    }

    /**
     * 模型動作因中斷而無法得知已持久化結果的封閉紀錄
     */
    record ActionInterrupted(String code, String description) implements ActionResult {

        public ActionInterrupted {
            requireText(code, "interrupted action code");
            requireText(description, "interrupted action description");
        }
    }

    /**
     * 回答驗證拒絕的完整判定
     */
    record AnswerRejected(AnswerVerdict verdict) implements ActionResult {

        public AnswerRejected {
            Objects.requireNonNull(verdict, "rejected answer verdict must not be null");
        }
    }

    /**
     * 回答驗證接受的結果
     */
    record AnswerAccepted() implements ActionResult {
    }

    /**
     * 釐清問題接受的結果
     */
    record ClarificationAccepted() implements ActionResult {
    }

    /**
     * 問題解析計畫已成為此 run 的 durable 事實
     */
    record QuestionPlanRecorded(QuestionPlan plan) implements ActionResult {

        public QuestionPlanRecorded {
            Objects.requireNonNull(plan, "recorded question plan must not be null");
        }
    }

    /**
     * 判斷結果是否可關閉指定類型的模型動作
     */
    default boolean matches(AgentAction action) {
        Objects.requireNonNull(action, "selected agent action must not be null");
        if (this instanceof ValidationRejected || this instanceof ActionInterrupted) {
            return true;
        }
        return switch (action) {
            case QueryAction ignored -> this instanceof QuerySucceeded
                    || this instanceof QueryFailed
                    || this instanceof QueryInvalidated;
            case ExecuteAction ignored -> this instanceof ExecuteCompleted;
            case AnswerAction ignored -> this instanceof AnswerRejected || this instanceof AnswerAccepted;
            case ClarifyAction ignored -> this instanceof ClarificationAccepted;
            case PlanAction planAction -> this instanceof QuestionPlanRecorded recorded
                    && recorded.plan().equals(planAction.plan());
        };
    }

    private static List<String> stableValues(List<String> values, String description) {
        Objects.requireNonNull(values, description + " must not be null");
        for (String value : values) {
            requireText(value, description + " value");
        }
        return List.copyOf(values);
    }

    private static void requireText(String value, String description) {
        Objects.requireNonNull(value, description + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be blank");
        }
    }
}
