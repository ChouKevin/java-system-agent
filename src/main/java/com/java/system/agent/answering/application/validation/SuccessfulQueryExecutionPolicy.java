package com.java.system.agent.answering.application.validation;

import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.action.QueryExecutionIdentity;
import com.java.system.agent.answering.domain.run.ActionResult;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.ModelInteraction;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 判斷同一 attempt 是否已成功完成相同 QUERY 外部執行
 */
final class SuccessfulQueryExecutionPolicy {

    boolean wasAlreadySuccessful(
            QueryAction action,
            AnalysisAttemptId currentAttemptId,
            List<ModelInteraction> interactions) {
        Objects.requireNonNull(action, "query action must not be null");
        Objects.requireNonNull(currentAttemptId, "current attempt ID must not be null");
        Objects.requireNonNull(interactions, "model interactions must not be null");
        QueryExecutionIdentity requested = QueryExecutionIdentity.from(action);
        Optional<QueryExecutionIdentity> pending = Optional.empty();
        for (ModelInteraction interaction : interactions) {
            switch (interaction) {
                case ModelInteraction.ActionSelected selected -> {
                    if (!selected.attemptId().equals(currentAttemptId)) {
                        continue;
                    }
                    pending = selected.action() instanceof QueryAction selectedQuery
                            ? Optional.of(QueryExecutionIdentity.from(selectedQuery))
                            : Optional.empty();
                }
                case ModelInteraction.ActionResultRecorded recorded -> {
                    if (!recorded.attemptId().equals(currentAttemptId)) {
                        continue;
                    }
                    if (recorded.result() instanceof ActionResult.QuerySucceeded
                            && pending.filter(requested::equals).isPresent()) {
                        return true;
                    }
                    pending = Optional.empty();
                }
                case ModelInteraction.MalformedResponse ignored -> {
                    // 無已選定的 QUERY 結果可供比較
                }
            }
        }
        return false;
    }
}
