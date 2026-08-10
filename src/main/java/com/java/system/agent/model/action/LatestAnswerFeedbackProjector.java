package com.java.system.agent.model.action;

import com.java.system.agent.answering.domain.answer.AnswerVerdict;
import com.java.system.agent.answering.domain.run.ActionResult;
import com.java.system.agent.answering.domain.run.ModelInteraction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 從 append-only 模型互動中投影最近一次回答拒絕與其後進度
 */
final class LatestAnswerFeedbackProjector {

    Optional<LatestAnswerFeedback> project(List<ModelInteraction> interactions) {
        Objects.requireNonNull(interactions, "model interactions must not be null");
        for (int index = interactions.size() - 1; index >= 0; index--) {
            ModelInteraction interaction = Objects.requireNonNull(
                    interactions.get(index), "model interactions must not contain null values");
            if (interaction instanceof ModelInteraction.ActionResultRecorded recorded
                    && recorded.result() instanceof ActionResult.AnswerRejected rejected) {
                return Optional.of(new LatestAnswerFeedback(
                        rejected.verdict(), subsequentResults(interactions, index + 1)));
            }
        }
        return Optional.empty();
    }

    private static List<ActionResult> subsequentResults(List<ModelInteraction> interactions, int startIndex) {
        List<ActionResult> results = new ArrayList<>();
        for (int index = startIndex; index < interactions.size(); index++) {
            ModelInteraction interaction = Objects.requireNonNull(
                    interactions.get(index), "model interactions must not contain null values");
            if (interaction instanceof ModelInteraction.ActionResultRecorded recorded) {
                results.add(recorded.result());
            }
        }
        return List.copyOf(results);
    }

    record LatestAnswerFeedback(AnswerVerdict verdict, List<ActionResult> subsequentResults) {

        LatestAnswerFeedback {
            Objects.requireNonNull(verdict, "latest rejected answer verdict must not be null");
            subsequentResults = List.copyOf(Objects.requireNonNull(
                    subsequentResults, "post-rejection results must not be null"));
        }
    }
}
