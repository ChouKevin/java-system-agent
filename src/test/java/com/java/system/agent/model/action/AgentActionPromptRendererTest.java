package com.java.system.agent.model.action;

import com.java.system.agent.answering.domain.action.AnswerAction;
import com.java.system.agent.answering.domain.action.ClarifyAction;
import com.java.system.agent.answering.domain.action.ExecuteAction;
import com.java.system.agent.answering.domain.action.ExternalHttpMethod;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.answer.AnswerDisposition;
import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.AnswerVerdict;
import com.java.system.agent.answering.domain.answer.StatementId;
import com.java.system.agent.answering.domain.answer.StatementType;
import com.java.system.agent.answering.domain.answer.StatementVerdict;
import com.java.system.agent.answering.domain.answer.StatementVerdictStatus;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.conversation.ConversationTurn;
import com.java.system.agent.answering.domain.conversation.ConversationTurnType;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.candidate.FollowUpCandidate;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.handle.CandidateHandleRef;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.run.ActionResult;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.ModelInteraction;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.out.AgentPromptContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentActionPromptRenderer 的 participant 歷史輸出測試
 */
class AgentActionPromptRendererTest {

    @Test
    void renders_prior_model_choices_and_results_in_order_as_the_final_section() {
        AnalysisRunId runId = new AnalysisRunId("run-3");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        HandleBinding binding = new HandleBinding(runId, attemptId, RevisionVector.empty());
        QueryAction query = new QueryAction(
                new CapabilityHandle("capability-1", binding),
                List.of(new CandidateHandleRef("candidate-1")),
                "resolve query",
                new CapabilityInputPayload("{\"scope\":\"all\"}"),
                "need evidence");
        ExecuteAction execute = new ExecuteAction(
                ExternalHttpMethod.PATCH,
                "https://example.invalid/items/1",
                Optional.of("{\"status\":\"active\"}"),
                "apply requested change");
        AnswerAction answer = new AnswerAction(new AnswerDocument(List.of(new AnswerStatement(
                new StatementId("statement-1"), StatementType.UNCERTAINTY, "answer text", Optional.empty(),
                Set.of(), Set.of()))));
        ClarifyAction clarify = new ClarifyAction("which item?", List.of(new CandidateHandleRef("candidate-2")),
                "need selection");
        AnswerVerdict verdict = new AnswerVerdict(
                AnswerDisposition.REJECTED,
                List.of(new StatementVerdict(new StatementId("statement-1"), StatementVerdictStatus.UNSUPPORTED,
                        "unsupported")),
                List.of("scope"), List.of("missing evidence"), List.of("rewrite"));
        AgentPromptContext context = new AgentPromptContext(
                "question", SessionHistory.empty(), runId, attemptId, Map.of(), Map.of(), Map.of(), Map.of(),
                List.of(
                        new ModelInteraction.ActionSelected(attemptId, query),
                        new ModelInteraction.ActionResultRecorded(attemptId,
                                new ActionResult.QuerySucceeded(List.of("candidate-3"), List.of("evidence-1"),
                                        List.of("observation-1"))),
                        new ModelInteraction.ActionSelected(attemptId, execute),
                        new ModelInteraction.ActionResultRecorded(attemptId,
                                new ActionResult.ExecuteCompleted(ActionResult.ExecuteOutcome.NOT_IMPLEMENTED,
                                        List.of("observation-2"), "not implemented")),
                        new ModelInteraction.ActionSelected(attemptId, query),
                        new ModelInteraction.ActionResultRecorded(attemptId,
                                new ActionResult.ValidationRejected("INVALID_ACTION", "invalid action")),
                        new ModelInteraction.MalformedResponse(attemptId, "malformed tool call"),
                        new ModelInteraction.ActionSelected(attemptId, answer),
                        new ModelInteraction.ActionResultRecorded(attemptId, new ActionResult.AnswerRejected(verdict)),
                        new ModelInteraction.ActionSelected(attemptId, clarify),
                        new ModelInteraction.ActionResultRecorded(attemptId,
                                new ActionResult.ActionInterrupted("RECOVERY_INTERRUPTED", "outcome unknown"))),
                Optional.empty(), new AttemptBudget(2, 0, 1, 0, 1, 0, 1, 0, 1, 0));

        String prompt = new AgentActionPromptRenderer().render(context);

        assertThat(prompt).endsWith("""
                Previous model choices and results:
                - attempt=attempt-1 selected QUERY: capability=capability-1, candidates=[candidate-1], questionToResolve=resolve query, payload={"scope":"all"}, rationale=need evidence
                - attempt=attempt-1 result QUERY_SUCCEEDED: candidateHandles=[candidate-3], evidenceHandles=[evidence-1], observationIds=[observation-1]
                - attempt=attempt-1 selected EXECUTE: method=PATCH, targetUrl=https://example.invalid/items/1, jsonBody={"status":"active"}, rationale=apply requested change
                - attempt=attempt-1 result EXECUTE_COMPLETED: outcome=NOT_IMPLEMENTED, observationIds=[observation-2], description=not implemented
                - attempt=attempt-1 selected QUERY: capability=capability-1, candidates=[candidate-1], questionToResolve=resolve query, payload={"scope":"all"}, rationale=need evidence
                - attempt=attempt-1 result VALIDATION_REJECTED: code=INVALID_ACTION, description=invalid action
                - attempt=attempt-1 malformed response: description=malformed tool call
                - attempt=attempt-1 selected ANSWER: document.statements=[{statementId=statement-1, type=UNCERTAINTY, text=answer text, claimId=none, citations=[], observationIds=[]}]
                - attempt=attempt-1 result ANSWER_REJECTED: disposition=REJECTED, statementVerdicts=[{statementId=statement-1, status=UNSUPPORTED, description=unsupported}], unaddressedParts=[scope], blockingUncertainties=[missing evidence], rejectionReasons=[rewrite]
                - attempt=attempt-1 selected CLARIFY: question=which item?, candidates=[candidate-2], reason=need selection
                - attempt=attempt-1 result ACTION_INTERRUPTED: code=RECOVERY_INTERRUPTED, description=outcome unknown
                """);
    }

    @Test
    void renders_each_history_turn_with_its_stable_participant_label() {
        SessionHistory history = new SessionHistory(List.of(
                new ConversationTurn(new AnalysisRunId("run-1"), new ParticipantRef("slack", "U123456"),
                        "請查詢付款流程", "付款流程如下", ConversationTurnType.ANSWER),
                new ConversationTurn(new AnalysisRunId("run-2"), new ParticipantRef("slack", "U789012"),
                        "也包含退款流程", "退款流程如下", ConversationTurnType.ANSWER)));
        AgentPromptContext context = new AgentPromptContext(
                "請查詢付款流程", history, new AnalysisRunId("run-3"), new AnalysisAttemptId("attempt-1"),
                Map.of(), Map.of(), Map.of(), Map.of(), List.of(), Optional.empty(),
                new AttemptBudget(2, 0, 1, 0, 1, 0, 1, 0, 1, 0));

        String prompt = new AgentActionPromptRenderer().render(context);

        assertThat(prompt).contains("""
                Session turns:
                participant[slack:U123456]: 請查詢付款流程
                assistant: 付款流程如下
                participant[slack:U789012]: 也包含退款流程
                assistant: 退款流程如下
                """);
        assertThat(AgentActionPromptRenderer.SYSTEM_INSTRUCTION)
                .startsWith("Choose exactly one registered planning tool call.\n");
        assertThat(prompt).contains("Remaining budget:\nagentSteps=2, queryExecutions=1, executeExecutions=1, actionRejections=1");
        assertThat(prompt).doesNotContain("- user:", "  type:");
    }

    @Test
    void rendersFollowUpDescriptionWithoutExposingItsCanonicalPayload() {
        RepositoryId repositoryId = new RepositoryId("repository-1");
        RepositoryRevision revision = new RepositoryRevision("revision-1");
        HandleBinding binding = new HandleBinding(new AnalysisRunId("run-3"), new AnalysisAttemptId("attempt-1"),
                RevisionVector.empty().pin(repositoryId, revision));
        CandidateHandle handle = new CandidateHandle("candidate-follow-up", binding, CandidateKind.FOLLOW_UP);
        CapabilityInputPayload payload = new CapabilityInputPayload("{\"sourceFile\":\"Sensitive.java\",\"line\":42}");
        FollowUpCandidate followUp = new FollowUpCandidate(repositoryId, revision, "codebase_get_source_segment", "v1",
                payload, "Read the remaining bounded source segment");
        AgentPromptContext context = new AgentPromptContext(
                "Read source", SessionHistory.empty(), new AnalysisRunId("run-3"), new AnalysisAttemptId("attempt-1"),
                Map.of(), Map.of(handle, new IssuedCandidate(handle, followUp)), Map.of(), Map.of(), List.of(), Optional.empty(),
                new AttemptBudget(2, 0, 1, 0, 1, 0, 1, 0, 1, 0));

        String prompt = new AgentActionPromptRenderer().render(context);

        assertThat(prompt).contains("Read the remaining bounded source segment");
        assertThat(prompt).doesNotContain(payload.value());
    }
}
