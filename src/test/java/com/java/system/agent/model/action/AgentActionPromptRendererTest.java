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
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.conversation.ConversationTurn;
import com.java.system.agent.answering.domain.conversation.ConversationTurnType;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.candidate.FollowUpCandidate;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.evidence.ArtifactRef;
import com.java.system.agent.answering.domain.evidence.EvidenceRef;
import com.java.system.agent.answering.domain.evidence.IssuedEvidence;
import com.java.system.agent.answering.domain.evidence.SemanticTarget;
import com.java.system.agent.answering.domain.evidence.SemanticTargetKind;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.handle.CandidateHandleRef;
import com.java.system.agent.answering.domain.handle.EvidenceHandle;
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
import com.java.system.agent.model.prompt.PromptResourceCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentActionPromptRenderer 的 resource projection 與敏感資料遮罩測試
 */
class AgentActionPromptRendererTest {

    @Test
    void projects_all_current_run_action_categories_and_results_at_the_model_interaction_tail() {
        AnalysisRunId runId = new AnalysisRunId("run-3");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        HandleBinding binding = new HandleBinding(runId, attemptId, RevisionVector.empty());
        QueryAction query = new QueryAction(new CapabilityHandle("capability-1", binding),
                List.of(new CandidateHandleRef("candidate-1")), "resolve query",
                new CapabilityInputPayload("{\"secret\":\"canonical-follow-up\"}"), "need evidence");
        ExecuteAction execute = new ExecuteAction(ExternalHttpMethod.PATCH,
                "https://secret.example.invalid/items/1?token=execute-secret",
                Optional.of("{\"credential\":\"execute-secret-body\"}"), "apply requested change");
        AnswerAction answer = new AnswerAction(new AnswerDocument(List.of(new AnswerStatement(
                new StatementId("statement-1"), StatementType.UNCERTAINTY, "answer text", Optional.empty(),
                Set.of(), Set.of()))));
        ClarifyAction clarify = new ClarifyAction("which item?", List.of(new CandidateHandleRef("candidate-2")),
                "need selection");
        AgentPromptContext context = new AgentPromptContext("question", SessionHistory.empty(), runId, attemptId,
                Map.of(), Map.of(), Map.of(), Map.of(), List.of(
                        new ModelInteraction.ActionSelected(attemptId, query),
                        new ModelInteraction.ActionResultRecorded(attemptId,
                                new ActionResult.QuerySucceeded(List.of("candidate-3"), List.of("evidence-1"),
                                        List.of("observation-1"))),
                        new ModelInteraction.ActionSelected(attemptId, execute),
                        new ModelInteraction.ActionResultRecorded(attemptId,
                                new ActionResult.ExecuteCompleted(ActionResult.ExecuteOutcome.NOT_IMPLEMENTED,
                                        List.of("observation-2"), "not implemented")),
                        new ModelInteraction.ActionSelected(attemptId, answer),
                        new ModelInteraction.ActionResultRecorded(attemptId, new ActionResult.AnswerRejected(
                                new AnswerVerdict(AnswerDisposition.REJECTED, List.of(new StatementVerdict(
                                        new StatementId("statement-1"), StatementVerdictStatus.UNSUPPORTED, "unsupported")),
                                        List.of("scope"), List.of("missing evidence"), List.of("rewrite")))),
                        new ModelInteraction.ActionSelected(attemptId, clarify),
                        new ModelInteraction.ActionResultRecorded(attemptId,
                                new ActionResult.ActionInterrupted("RECOVERY_INTERRUPTED", "outcome unknown"))),
                Optional.empty(), new AttemptBudget(2, 0, 1, 0, 1, 0, 1, 0, 1, 0));

        Map<String, Object> projection = renderer().project(context);

        assertThat(projection).containsOnlyKeys("originalQuestion", "sessionTurns", "capabilities", "candidates",
                "evidence", "evidenceCoverage", "observations", "latestRejection", "remainingBudget",
                "modelInteractions");
        assertThat(projection.get("originalQuestion")).isEqualTo("question");
        String interactions = (String) projection.get("modelInteractions");
        assertThat(interactions).containsSubsequence(
                AgentActionFingerprint.from(query).value(), "QUERY_SUCCEEDED",
                AgentActionFingerprint.from(execute).value(), "EXECUTE_COMPLETED",
                AgentActionFingerprint.from(answer).value(), "ANSWER_REJECTED",
                AgentActionFingerprint.from(clarify).value(), "ACTION_INTERRUPTED");
        assertThat(interactions).doesNotContain("canonical-follow-up", "secret.example.invalid", "execute-secret",
                "execute-secret-body");
    }

    @Test
    void projects_concluded_session_turns_and_follow_up_metadata_without_copying_payloads() {
        RepositoryId repositoryId = new RepositoryId("repository-1");
        RepositoryRevision revision = new RepositoryRevision("revision-1");
        HandleBinding binding = new HandleBinding(new AnalysisRunId("run-3"), new AnalysisAttemptId("attempt-1"),
                RevisionVector.empty().pin(repositoryId, revision));
        CandidateHandle candidateHandle = new CandidateHandle("candidate-follow-up", binding, CandidateKind.FOLLOW_UP);
        CapabilityInputPayload payload = new CapabilityInputPayload("{\"sourceFile\":\"Sensitive.java\",\"line\":42}");
        FollowUpCandidate followUp = new FollowUpCandidate(repositoryId, revision, "codebase_get_source_segment", "v1",
                payload, "Read the remaining bounded source segment");
        SessionHistory history = new SessionHistory(List.of(
                new ConversationTurn(new AnalysisRunId("run-1"), new ParticipantRef("slack", "U123456"),
                        "first question", "first answer", ConversationTurnType.ANSWER),
                new ConversationTurn(new AnalysisRunId("run-2"), new ParticipantRef("slack", "U789012"),
                        "second question", "second answer", ConversationTurnType.ANSWER)));
        AgentPromptContext context = new AgentPromptContext("Read source", history, new AnalysisRunId("run-3"),
                new AnalysisAttemptId("attempt-1"), Map.of(), Map.of(candidateHandle, new IssuedCandidate(candidateHandle,
                followUp)), Map.of(), Map.of(), List.of(), Optional.empty(),
                new AttemptBudget(2, 0, 1, 0, 1, 0, 1, 0, 1, 0));

        Map<String, Object> projection = renderer().project(context);

        assertThat((String) projection.get("sessionTurns")).containsSubsequence(
                "first question", "first answer", "second question", "second answer");
        assertThat((String) projection.get("candidates")).contains("candidate-follow-up",
                "codebase_get_source_segment@v1").doesNotContain(payload.value());
    }

    @Test
    void renders_the_complete_projection_through_the_injected_catalog() {
        PromptResourceCatalog catalog = mock(PromptResourceCatalog.class);
        AgentActionPromptRenderer renderer = new AgentActionPromptRenderer(catalog);
        AgentPromptContext context = minimalContext();
        when(catalog.renderActionContext(anyMap())).thenReturn("resource-rendered-context");

        String rendered = renderer.render(context);

        assertThat(rendered).isEqualTo("resource-rendered-context");
        verify(catalog).renderActionContext(renderer.project(context));
    }

    private static AgentActionPromptRenderer renderer() {
        return new AgentActionPromptRenderer(mock(PromptResourceCatalog.class));
    }

    private static AgentPromptContext minimalContext() {
        return new AgentPromptContext("question", SessionHistory.empty(), new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"), Map.of(), Map.of(), Map.of(), Map.of(), List.of(), Optional.empty(),
                new AttemptBudget(1, 0, 1, 0, 1, 0, 1, 0, 1, 0));
    }
}
