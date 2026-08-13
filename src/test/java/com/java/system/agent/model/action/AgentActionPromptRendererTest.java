package com.java.system.agent.model.action;

import com.java.system.agent.answering.domain.action.AnswerAction;
import com.java.system.agent.answering.domain.action.ClarifyAction;
import com.java.system.agent.answering.domain.action.ExecuteAction;
import com.java.system.agent.answering.domain.action.ExternalHttpMethod;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.action.PlanAction;
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
import com.java.system.agent.answering.domain.handle.EvidenceHandleRef;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.observation.ObservationId;
import com.java.system.agent.answering.domain.run.ActionResult;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.ModelInteraction;
import com.java.system.agent.answering.domain.plan.InformationNeed;
import com.java.system.agent.answering.domain.plan.InformationNeedId;
import com.java.system.agent.answering.domain.plan.NeedResolution;
import com.java.system.agent.answering.domain.plan.NeedResolutionStatus;
import com.java.system.agent.answering.domain.plan.QuestionPlan;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.out.AgentPromptContext;
import com.java.system.agent.model.prompt.PromptResourceCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        CapabilityHandle queryCapability = new CapabilityHandle("capability-1", binding);
        CapabilityInputPayload queryArguments = new CapabilityInputPayload(
                "{\"kinds\":[\"TYPE\"],\"terms\":[{\"value\":\"Payment\"}]}");
        QueryAction query = new QueryAction(queryCapability, "resolve query", queryArguments, "need evidence");
        ExecuteAction execute = new ExecuteAction(ExternalHttpMethod.PATCH,
                "https://secret.example.invalid/items/1?token=execute-secret",
                Optional.of("{\"credential\":\"execute-secret-body\"}"), "apply requested change");
        AnswerAction answer = new AnswerAction(new AnswerDocument(List.of(new AnswerStatement(
                new StatementId("statement-1"), StatementType.UNCERTAINTY, "answer text", Optional.empty(),
                Set.of(), Set.of()))), List.of(new NeedResolution(new InformationNeedId("need-1"),
                NeedResolutionStatus.UNAVAILABLE, Set.of(), Set.of(new ObservationId("observation-1")))));
        ClarifyAction clarify = new ClarifyAction("which item?", List.of(new CandidateHandleRef("candidate-2")),
                "need selection");
        AgentPromptContext context = new AgentPromptContext("question", SessionHistory.empty(), runId, attemptId,
                Map.of(queryCapability, new CapabilityPolicy("codebase_discover_concepts", "v1")),
                Map.of(), Map.of(), Map.of(), List.of(
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

        PromptResourceCatalog catalog = mock(PromptResourceCatalog.class);
        AgentActionPromptRenderer renderer = new AgentActionPromptRenderer(catalog);
        when(catalog.renderLatestAnswerFeedback(anyMap())).thenReturn("typed-feedback-fragment");

        List<String> currentToolNames = authority("agent_submit_answer", "agent_request_clarification");

        Map<String, Object> projection = renderer.project(context, currentToolNames);

        assertThat(projection).containsOnlyKeys("originalQuestion", "sessionTurns", "currentlyCallableTools", "candidates",
                "evidence", "evidenceCoverage", "observations", "questionPlan", "latestAnswerFeedback", "latestRejection",
                "remainingBudget", "modelInteractions");
        assertThat(projection).doesNotContainKey("capabilities");
        assertThat(projection.get("currentlyCallableTools")).isEqualTo("- agent_submit_answer\n- agent_request_clarification\n");
        assertThat(projection.get("latestAnswerFeedback")).isEqualTo("typed-feedback-fragment");
        assertThat(projection.get("originalQuestion")).isEqualTo("question");
        String interactions = (String) projection.get("modelInteractions");
        assertThat(interactions).containsSubsequence(
                AgentActionFingerprint.from(query).value(), "QUERY_SUCCEEDED",
                AgentActionFingerprint.from(execute).value(), "EXECUTE_COMPLETED",
                AgentActionFingerprint.from(answer).value(), "ANSWER_REJECTED",
                AgentActionFingerprint.from(clarify).value(), "ACTION_INTERRUPTED");
        assertThat(interactions).contains("QUERY: tool=codebase_discover_concepts@v1",
                "arguments=" + queryArguments.value(),
                "needId=need-1", "status=UNAVAILABLE", "observationIds=[observation-1]");
        assertThat(interactions).doesNotContain("candidateHandles", "candidate-1", "payloadSummary=sha256=",
                "secret.example.invalid", "execute-secret", "execute-secret-body");
        verify(catalog).renderLatestAnswerFeedback(argThat(values -> {
            assertThat(values).containsOnlyKeys("disposition", "statementVerdicts", "unaddressedParts",
                    "blockingUncertainties", "rejectionReasons", "subsequentResults");
            assertThat(values.get("disposition")).isEqualTo(AnswerDisposition.REJECTED);
            assertThat((String) values.get("statementVerdicts")).contains("statement-1", "UNSUPPORTED");
            assertThat((String) values.get("subsequentResults")).contains("ACTION_INTERRUPTED")
                    .doesNotContain("ANSWER_REJECTED");
            assertThat(values.values().toString()).doesNotContain("answer text", "resolve query",
                    "canonical-follow-up", "secret.example.invalid", "execute-secret", "execute-secret-body");
            return true;
        }));
    }

    @Test
    void projects_the_committed_question_plan_in_its_persisted_need_order() {
        AnalysisRunId runId = new AnalysisRunId("run-5");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        QuestionPlan plan = new QuestionPlan(List.of(
                new InformationNeed(new InformationNeedId("need-2"), "Resolve the boundary"),
                new InformationNeed(new InformationNeedId("need-1"), "Trace the entry point")));
        AgentPromptContext context = new AgentPromptContext("question", SessionHistory.empty(), runId, attemptId,
                Map.of(), Map.of(), Map.of(), Map.of(), List.of(
                        new ModelInteraction.ActionSelected(attemptId, new PlanAction(plan)),
                        new ModelInteraction.ActionResultRecorded(attemptId, new ActionResult.QuestionPlanRecorded(plan))),
                Optional.empty(), new AttemptBudget(3, 1, 1, 0, 1, 0, 1, 0, 1, 0));

        Map<String, Object> projection = renderer().project(context, authority("agent_request_clarification"));

        assertThat(projection.get("questionPlan")).isEqualTo("- need-2: Resolve the boundary\n"
                + "- need-1: Trace the entry point\n");
    }

    @Test
    void renders_an_evidence_backed_unavailable_resolution_without_observations_in_action_history() {
        AnalysisRunId runId = new AnalysisRunId("run-1");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        AnswerAction answer = new AnswerAction(new AnswerDocument(List.of(new AnswerStatement(
                new StatementId("statement-1"), StatementType.UNCERTAINTY, "boundary is unavailable", Optional.empty(),
                Set.of(new EvidenceHandleRef("evidence-1")), Set.of()))), List.of(new NeedResolution(
                new InformationNeedId("need-1"), NeedResolutionStatus.UNAVAILABLE,
                Set.of(new EvidenceHandleRef("evidence-1")), Set.of())));
        AgentPromptContext context = new AgentPromptContext("question", SessionHistory.empty(), runId, attemptId,
                Map.of(), Map.of(), Map.of(), Map.of(),
                List.of(new ModelInteraction.ActionSelected(attemptId, answer)), Optional.empty(),
                new AttemptBudget(1, 0, 1, 0, 1, 0, 1, 0, 1, 0));

        String interactions = (String) renderer().project(context, List.of()).get("modelInteractions");

        assertThat(interactions).contains("needId=need-1", "status=UNAVAILABLE",
                "evidenceHandles=[evidence-1]", "observationIds=[]");
    }

    @Test
    void renders_a_validation_rejection_code_in_model_interaction_history() {
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        AgentPromptContext context = new AgentPromptContext("question", SessionHistory.empty(), new AnalysisRunId("run-1"),
                attemptId, Map.of(), Map.of(), Map.of(), Map.of(), List.of(
                        new ModelInteraction.ActionResultRecorded(attemptId,
                                new ActionResult.ValidationRejected("LIMITATION_OBSERVATION_REQUIRED",
                                        "LIMITATION_OBSERVATION_REQUIRED"))), Optional.empty(),
                new AttemptBudget(1, 0, 1, 0, 1, 0, 1, 0, 1, 0));

        String interactions = (String) renderer().project(context, List.of()).get("modelInteractions");

        assertThat(interactions).contains("VALIDATION_REJECTED: code=LIMITATION_OBSERVATION_REQUIRED");
    }

    @Test
    void projects_concluded_session_turns_and_preserves_stale_follow_up_candidates_as_generic_context() {
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

        Map<String, Object> projection = renderer().project(context, authority("agent_request_clarification"));

        assertThat((String) projection.get("sessionTurns")).containsSubsequence(
                "first question", "first answer", "second question", "second answer");
        assertThat((String) projection.get("candidates")).contains("candidate-follow-up", "repository-1@revision-1",
                "Read the remaining bounded source segment").doesNotContain("targetTool=", "suggestedArguments=",
                "allowedCandidateHandles", payload.value());
    }

    @Test
    void projects_only_current_tools_and_omits_empty_historical_evidence_coverage() {
        AnalysisRunId runId = new AnalysisRunId("run-4");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        RepositoryId repositoryId = new RepositoryId("repository-1");
        RepositoryRevision repositoryRevision = new RepositoryRevision("revision-1");
        HandleBinding binding = new HandleBinding(runId, attemptId,
                RevisionVector.empty().pin(repositoryId, repositoryRevision));
        CapabilityHandle issuedButUnused = new CapabilityHandle("capability-1", binding);
        CapabilityPolicy historicalCapability = new CapabilityPolicy("codebase_find_internal_references", "v1");
        CapabilityHandle producedCapability = new CapabilityHandle("capability-2", binding);
        CapabilityPolicy producingCapability = new CapabilityPolicy("codebase_get_method_source", "v1");
        EvidenceHandle evidenceHandle = new EvidenceHandle("evidence-1", binding);
        EvidenceRef evidence = new EvidenceRef("semantic", repositoryId, repositoryRevision,
                new SemanticTarget(SemanticTargetKind.SYMBOL, "Type#method",
                Optional.empty()), "method source", List.of(), new ArtifactRef("digest-1"));
        QueryAction query = new QueryAction(producedCapability, "read method source",
                new CapabilityInputPayload("{}"), "need source evidence");
        AgentPromptContext context = new AgentPromptContext("question", SessionHistory.empty(), runId, attemptId,
                Map.of(issuedButUnused, historicalCapability, producedCapability, producingCapability), Map.of(),
                Map.of(evidenceHandle, new IssuedEvidence(evidenceHandle, evidence)), Map.of(), List.of(
                        new ModelInteraction.ActionSelected(attemptId, query),
                        new ModelInteraction.ActionResultRecorded(attemptId,
                                new ActionResult.QuerySucceeded(List.of(), List.of("evidence-1"), List.of()))), Optional.empty(),
                new AttemptBudget(1, 0, 1, 0, 1, 0, 1, 0, 1, 0));

        Map<String, Object> projection = renderer().project(context,
                authority("agent_submit_answer", "agent_request_clarification"));

        assertThat(projection.get("currentlyCallableTools"))
                .isEqualTo("- agent_submit_answer\n- agent_request_clarification\n");
        assertThat(projection.get("evidenceCoverage")).isEqualTo("- codebase_get_method_source@v1: evidence-1\n");
    }

    @Test
    void projects_current_tools_without_candidate_authority() {
        RepositoryId repositoryId = new RepositoryId("repository-1");
        RepositoryRevision revision = new RepositoryRevision("revision-1");
        HandleBinding binding = new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"),
                RevisionVector.empty().pin(repositoryId, revision));
        CandidateHandle methodHandle = new CandidateHandle("candidate-method", binding, CandidateKind.FOLLOW_UP);
        CandidateHandle membersHandle = new CandidateHandle("candidate-members", binding, CandidateKind.FOLLOW_UP);
        CandidateHandle unissuedHandle = new CandidateHandle("candidate-unissued", binding, CandidateKind.FOLLOW_UP);
        CapabilityHandle methodCapability = new CapabilityHandle("capability-method", binding);
        CapabilityHandle membersCapability = new CapabilityHandle("capability-members", binding);
        AgentPromptContext context = new AgentPromptContext("question", SessionHistory.empty(), new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"), Map.of(
                methodCapability, new CapabilityPolicy("codebase_get_method_source", "v1"),
                membersCapability, new CapabilityPolicy("codebase_discover_type_members", "v1")), Map.of(
                methodHandle, new IssuedCandidate(methodHandle, new FollowUpCandidate(repositoryId, revision,
                        "codebase_get_method_source", "v1", new CapabilityInputPayload("{}"), "Method source candidate")),
                membersHandle, new IssuedCandidate(membersHandle, new FollowUpCandidate(repositoryId, revision,
                        "codebase_discover_type_members", "v1", new CapabilityInputPayload("{}"), "Type members candidate")),
                unissuedHandle, new IssuedCandidate(unissuedHandle, new FollowUpCandidate(repositoryId, revision,
                        "codebase_get_source_segment", "v1", new CapabilityInputPayload("{}"), "Unissued candidate"))),
                Map.of(), Map.of(), List.of(), Optional.empty(),
                new AttemptBudget(1, 0, 1, 0, 1, 0, 1, 0, 1, 0));
        List<String> authority = List.of("codebase_get_method_source", "codebase_discover_type_members",
                "agent_submit_answer");

        Map<String, Object> projection = renderer().project(context, authority);

        assertThat(projection.get("currentlyCallableTools")).isEqualTo("- codebase_get_method_source\n"
                + "- codebase_discover_type_members\n- agent_submit_answer\n");
        assertThat((String) projection.get("candidates")).contains(
                "candidate-method", "candidate-members", "candidate-unissued",
                "Method source candidate", "Type members candidate", "Unissued candidate",
                "targetTool=codebase_get_method_source@v1, suggestedArguments={}",
                "targetTool=codebase_discover_type_members@v1, suggestedArguments={}")
                .doesNotContain("targetTool=codebase_get_source_segment@v1", "allowedCandidateHandles",
                        "repoId", "expectedRevision");
        assertThat((String) projection.get("currentlyCallableTools"))
                .doesNotContain("candidate-unissued", "codebase_get_source_segment", "Method source candidate",
                        "Type members candidate", "Unissued candidate");
    }

    @Test
    void keeps_a_successful_follow_up_as_history_without_suggesting_the_same_execution_again() {
        AnalysisRunId runId = new AnalysisRunId("run-1");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        RepositoryId repositoryId = new RepositoryId("repository-1");
        RepositoryRevision revision = new RepositoryRevision("revision-1");
        HandleBinding binding = new HandleBinding(runId, attemptId,
                RevisionVector.empty().pin(repositoryId, revision));
        CapabilityHandle capabilityHandle = new CapabilityHandle("capability-members", binding);
        CapabilityInputPayload payload = new CapabilityInputPayload("{\"sourceType\":\"FeeQuote\"}");
        QueryAction successfulQuery = new QueryAction(
                capabilityHandle, "Inspect FeeQuote", payload, "Need fee structure");
        CandidateHandle candidateHandle = new CandidateHandle(
                "candidate-members", binding, CandidateKind.FOLLOW_UP);
        FollowUpCandidate followUp = new FollowUpCandidate(
                repositoryId,
                revision,
                "codebase_discover_type_members",
                "v1",
                payload,
                "Inspect FeeQuote members");
        CandidateHandle alternativeCandidateHandle = new CandidateHandle(
                "candidate-policy-members", binding, CandidateKind.FOLLOW_UP);
        CapabilityInputPayload alternativePayload = new CapabilityInputPayload(
                "{\"sourceType\":\"PaymentFeePolicy\"}");
        FollowUpCandidate alternativeFollowUp = new FollowUpCandidate(
                repositoryId,
                revision,
                "codebase_discover_type_members",
                "v1",
                alternativePayload,
                "Inspect PaymentFeePolicy members");
        AgentPromptContext context = new AgentPromptContext(
                "question",
                SessionHistory.empty(),
                runId,
                attemptId,
                Map.of(capabilityHandle, new CapabilityPolicy("codebase_discover_type_members", "v1")),
                Map.of(
                        candidateHandle, new IssuedCandidate(candidateHandle, followUp),
                        alternativeCandidateHandle,
                        new IssuedCandidate(alternativeCandidateHandle, alternativeFollowUp)),
                Map.of(),
                Map.of(),
                List.of(
                        new ModelInteraction.ActionSelected(attemptId, successfulQuery),
                        new ModelInteraction.ActionResultRecorded(attemptId,
                                new ActionResult.QuerySucceeded(List.of("candidate-members"), List.of(), List.of()))),
                Optional.empty(),
                new AttemptBudget(2, 1, 2, 1, 1, 0, 2, 0, 1, 0));

        String candidates = (String) renderer().project(
                context, authority("codebase_discover_type_members")).get("candidates");

        assertThat(candidates).contains(
                "candidate-members: kind=FOLLOW_UP, repository=repository-1@revision-1, "
                        + "description=Inspect FeeQuote members\n",
                "candidate-policy-members: kind=FOLLOW_UP, repository=repository-1@revision-1, "
                        + "description=Inspect PaymentFeePolicy members, "
                        + "targetTool=codebase_discover_type_members@v1, "
                        + "suggestedArguments=" + alternativePayload.value());
    }

    @ParameterizedTest
    @MethodSource("followUpCapabilityStates")
    void renders_follow_up_suggestions_only_for_an_exact_current_capability(
            String scenario,
            CapabilityPolicy issuedCapability,
            HandleBinding issuedCapabilityBinding,
            boolean suggestionExpected) {
        AnalysisRunId runId = new AnalysisRunId("run-1");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        RepositoryId repositoryId = new RepositoryId("repository-1");
        RepositoryRevision revision = new RepositoryRevision("revision-1");
        HandleBinding candidateBinding = new HandleBinding(runId, attemptId,
                RevisionVector.empty().pin(repositoryId, revision));
        CandidateHandle candidateHandle = new CandidateHandle("candidate-follow-up", candidateBinding,
                CandidateKind.FOLLOW_UP);
        CapabilityInputPayload payload = new CapabilityInputPayload("{\"target\":\"Member\"}");
        FollowUpCandidate followUp = new FollowUpCandidate(repositoryId, revision, "callers", "v1", payload,
                "Inspect member callers");
        CapabilityHandle capabilityHandle = new CapabilityHandle("capability-callers", issuedCapabilityBinding);
        AgentPromptContext context = new AgentPromptContext("question", SessionHistory.empty(), runId, attemptId,
                Map.of(capabilityHandle, issuedCapability), Map.of(candidateHandle, new IssuedCandidate(candidateHandle, followUp)),
                Map.of(), Map.of(), List.of(), Optional.empty(),
                new AttemptBudget(1, 0, 1, 0, 1, 0, 1, 0, 1, 0));

        String candidates = (String) renderer().project(context, authority("callers")).get("candidates");

        assertThat(candidates).as(scenario).contains("Inspect member callers");
        if (suggestionExpected) {
            assertThat(candidates).contains("targetTool=callers@v1, suggestedArguments=" + payload.value());
        } else {
            assertThat(candidates).doesNotContain("targetTool=", "suggestedArguments=");
        }
    }

    private static Stream<Arguments> followUpCapabilityStates() {
        return Stream.of(
                Arguments.of("exact current policy", new CapabilityPolicy("callers", "v1"),
                        new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"),
                                RevisionVector.empty()), true),
                Arguments.of("version mismatch", new CapabilityPolicy("callers", "v2"),
                        new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"),
                                RevisionVector.empty()), false),
                Arguments.of("stale attempt binding", new CapabilityPolicy("callers", "v1"),
                        new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-0"),
                                RevisionVector.empty()), false));
    }

    @Test
    void renders_the_complete_projection_through_the_injected_catalog() {
        PromptResourceCatalog catalog = mock(PromptResourceCatalog.class);
        AgentActionPromptRenderer renderer = new AgentActionPromptRenderer(catalog);
        AgentPromptContext context = minimalContext();
        when(catalog.renderActionContext(anyMap())).thenReturn("resource-rendered-context");

        List<String> currentToolNames = authority("agent_submit_answer");

        String rendered = renderer.render(context, currentToolNames);

        assertThat(rendered).isEqualTo("resource-rendered-context");
        verify(catalog).renderActionContext(renderer.project(context, currentToolNames));
    }

    @Test
    void omits_latest_answer_feedback_when_no_answer_was_rejected() {
        PromptResourceCatalog catalog = mock(PromptResourceCatalog.class);
        AgentActionPromptRenderer renderer = new AgentActionPromptRenderer(catalog);

        Map<String, Object> projection = renderer.project(minimalContext(), authority("agent_submit_answer"));

        assertThat(projection.get("latestAnswerFeedback")).isEqualTo("");
        verify(catalog, never()).renderLatestAnswerFeedback(anyMap());
    }

    private static AgentActionPromptRenderer renderer() {
        return new AgentActionPromptRenderer(mock(PromptResourceCatalog.class));
    }

    private static List<String> authority(String... toolNames) {
        return List.of(toolNames);
    }

    private static AgentPromptContext minimalContext() {
        return new AgentPromptContext("question", SessionHistory.empty(), new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"), Map.of(), Map.of(), Map.of(), Map.of(), List.of(), Optional.empty(),
                new AttemptBudget(1, 0, 1, 0, 1, 0, 1, 0, 1, 0));
    }
}
