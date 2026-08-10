package com.java.system.agent.model.action;

import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.answer.AnswerDisposition;
import com.java.system.agent.answering.domain.answer.AnswerVerdict;
import com.java.system.agent.answering.domain.answer.StatementId;
import com.java.system.agent.answering.domain.answer.StatementVerdict;
import com.java.system.agent.answering.domain.answer.StatementVerdictStatus;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.run.ActionResult;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.ModelInteraction;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LatestAnswerFeedbackProjectorTest {

    private static final AnalysisRunId RUN_ID = new AnalysisRunId("run-1");
    private static final AnalysisAttemptId ATTEMPT_ID = new AnalysisAttemptId("attempt-1");

    @Test
    void projectsOnlyTheLatestRejectedAnswerAndItsSubsequentResultsInAppendOrder() {
        AnswerVerdict olderVerdict = rejectedVerdict("older-statement");
        AnswerVerdict latestVerdict = rejectedVerdict("latest-statement");
        ActionResult.QuerySucceeded latestSuccess = new ActionResult.QuerySucceeded(
                List.of("candidate-latest"), List.of("evidence-latest"), List.of("observation-latest"));
        ActionResult.QueryFailed latestFailure = new ActionResult.QueryFailed(
                List.of("observation-failed"), "provider unavailable");
        List<ModelInteraction> interactions = List.of(
                recorded(new ActionResult.AnswerRejected(olderVerdict)),
                recorded(new ActionResult.QuerySucceeded(
                        List.of("candidate-old"), List.of("evidence-old"), List.of("observation-old"))),
                recorded(new ActionResult.AnswerRejected(latestVerdict)),
                new ModelInteraction.ActionSelected(ATTEMPT_ID, queryAction()),
                recorded(latestSuccess),
                recorded(latestFailure));

        LatestAnswerFeedbackProjector.LatestAnswerFeedback feedback = new LatestAnswerFeedbackProjector()
                .project(interactions)
                .orElseThrow();

        assertThat(feedback.verdict()).isEqualTo(latestVerdict);
        assertThat(feedback.subsequentResults()).containsExactly(latestSuccess, latestFailure);
    }

    @Test
    void returnsEmptyWhenNoAnswerWasRejected() {
        assertThat(new LatestAnswerFeedbackProjector().project(List.of(
                recorded(new ActionResult.QuerySucceeded(List.of(), List.of(), List.of())))))
                .isEmpty();
    }

    private static ModelInteraction.ActionResultRecorded recorded(ActionResult result) {
        return new ModelInteraction.ActionResultRecorded(ATTEMPT_ID, result);
    }

    private static AnswerVerdict rejectedVerdict(String statementId) {
        return new AnswerVerdict(AnswerDisposition.REJECTED, List.of(new StatementVerdict(
                new StatementId(statementId), StatementVerdictStatus.UNSUPPORTED, "unsupported")), List.of(),
                List.of(), List.of("rewrite"));
    }

    private static QueryAction queryAction() {
        HandleBinding binding = new HandleBinding(RUN_ID, ATTEMPT_ID, RevisionVector.empty());
        return new QueryAction(new CapabilityHandle("capability-1", binding), List.of(), "resolve query",
                new CapabilityInputPayload("{}"), "need evidence");
    }
}
