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
                new CapabilityInputPayload("{\"secret\":\"canonical-follow-up\"}"),
                "need evidence");
        ExecuteAction execute = new ExecuteAction(
                ExternalHttpMethod.PATCH,
                "https://secret.example.invalid/items/1?token=execute-secret",
                Optional.of("{\"credential\":\"execute-secret-body\"}"),
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
                - attempt=attempt-1 selected QUERY: capability=capability-1, candidates=[candidate-1], questionToResolve=resolve query, payloadSummary=sha256=f05c6026d4bfadefc1db7002777678ef10ed226118b19fc1731a70ee8ed33192, characters=32, rationale=need evidence
                - attempt=attempt-1 result QUERY_SUCCEEDED: candidateHandles=[candidate-3], evidenceHandles=[evidence-1], observationIds=[observation-1]
                - attempt=attempt-1 selected EXECUTE: method=PATCH, targetUrlSummary=sha256=40112b3220b2a402fe33ff4f3c9014ba6253b29ec1803751d6e094173c970e5a, characters=59, jsonBodySummary=sha256=18fb61743402320294aa1f70bb215b13699f4a6e6ba529a162081cc52713450f, characters=36, rationale=apply requested change
                - attempt=attempt-1 result EXECUTE_COMPLETED: outcome=NOT_IMPLEMENTED, observationIds=[observation-2], description=not implemented
                - attempt=attempt-1 selected QUERY: capability=capability-1, candidates=[candidate-1], questionToResolve=resolve query, payloadSummary=sha256=f05c6026d4bfadefc1db7002777678ef10ed226118b19fc1731a70ee8ed33192, characters=32, rationale=need evidence
                - attempt=attempt-1 result VALIDATION_REJECTED: code=INVALID_ACTION, description=invalid action
                - attempt=attempt-1 malformed response: description=malformed tool call
                - attempt=attempt-1 selected ANSWER: document.statements=[{statementId=statement-1, type=UNCERTAINTY, text=answer text, claimId=none, citations=[], observationIds=[]}]
                - attempt=attempt-1 result ANSWER_REJECTED: disposition=REJECTED, statementVerdicts=[{statementId=statement-1, status=UNSUPPORTED, description=unsupported}], unaddressedParts=[scope], blockingUncertainties=[missing evidence], rejectionReasons=[rewrite]
                - attempt=attempt-1 selected CLARIFY: question=which item?, candidates=[candidate-2], reason=need selection
                - attempt=attempt-1 result ACTION_INTERRUPTED: code=RECOVERY_INTERRUPTED, description=outcome unknown
                """);
        assertThat(prompt).doesNotContain(
                "canonical-follow-up",
                "secret.example.invalid",
                "execute-secret",
                "execute-secret-body");
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
    void requires_explicit_deliverables_to_be_resolved_before_answering() {
        assertThat(AgentActionPromptRenderer.SYSTEM_INSTRUCTION)
                .contains("Treat every explicitly requested deliverable and evidence type as required")
                .contains("Do not submit an answer while any required evidence type is absent")
                .contains("Prefer a query that supplies a missing evidence type")
                .contains("Do not substitute source text for explicitly requested call-graph")
                .contains("Respect every tool schema limit such as maxItems");
    }

    @Test
    void rendersFollowUpSelectionMetadataWithoutExposingItsCanonicalPayload() {
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

        assertThat(prompt).contains("""
                Candidates:
                - candidate-follow-up: kind=FOLLOW_UP, repository=repository-1@revision-1, description=Read the remaining bounded source segment, targetCapability=codebase_get_source_segment@v1
                """);
        assertThat(prompt).doesNotContain(payload.value());
        assertThat(AgentActionPromptRenderer.SYSTEM_INSTRUCTION)
                .contains("Candidate handles must come from Candidates, never Evidence.");
    }

    @Test
    void labels_evidence_with_the_capability_recorded_in_query_history() {
        RepositoryId repositoryId = new RepositoryId("repository-1");
        RepositoryRevision revision = new RepositoryRevision("revision-1");
        AnalysisRunId runId = new AnalysisRunId("run-3");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        HandleBinding binding = new HandleBinding(runId, attemptId, RevisionVector.empty().pin(repositoryId, revision));
        CapabilityHandle capabilityHandle = new CapabilityHandle("capability-1", binding);
        CapabilityPolicy capability = new CapabilityPolicy(
                "codebase_outgoing_call_graph", "v1", Set.of(CandidateKind.REPOSITORY), 1, 1);
        CapabilityHandle missingCapabilityHandle = new CapabilityHandle("capability-2", binding);
        CapabilityPolicy missingCapability = new CapabilityPolicy(
                "codebase_find_internal_references", "v1", Set.of(CandidateKind.SEMANTIC_TARGET), 1, 1);
        EvidenceHandle evidenceHandle = new EvidenceHandle("evidence-1", binding);
        IssuedEvidence evidence = new IssuedEvidence(evidenceHandle, new EvidenceRef(
                "semantic", repositoryId, revision,
                new SemanticTarget(SemanticTargetKind.SYMBOL, "Orders#create", Optional.empty()),
                "root=Orders#create", List.of(), new ArtifactRef("digest-1")));
        QueryAction query = new QueryAction(
                capabilityHandle, List.of(new CandidateHandleRef("candidate-1")), "Trace the call graph",
                new CapabilityInputPayload("{}"), "Need graph evidence");
        AgentPromptContext context = new AgentPromptContext(
                "Trace orders", SessionHistory.empty(), runId, attemptId,
                Map.of(capabilityHandle, capability, missingCapabilityHandle, missingCapability),
                Map.of(), Map.of(evidenceHandle, evidence), Map.of(),
                List.of(
                        new ModelInteraction.ActionSelected(attemptId, query),
                        new ModelInteraction.ActionResultRecorded(attemptId,
                                new ActionResult.QuerySucceeded(List.of(), List.of(evidenceHandle.value()), List.of()))),
                Optional.empty(), new AttemptBudget(2, 1, 2, 1, 1, 0, 1, 0, 1, 0));

        String prompt = new AgentActionPromptRenderer().render(context);

        assertThat(prompt).contains(
                "- evidence-1 [producedBy=codebase_outgoing_call_graph@v1]: root=Orders#create");
        assertThat(prompt)
                .contains("- codebase_outgoing_call_graph@v1: evidence-1")
                .contains("- codebase_find_internal_references@v1: none");
        assertThat(AgentActionPromptRenderer.SYSTEM_INSTRUCTION)
                .contains("implementation evidence requires codebase_discover_method_implementations")
                .contains("internal-reference evidence requires codebase_find_internal_references")
                .contains("Use codebase_discover_method_implementations only on an abstract method declared by an interface or abstract class")
                .contains("use codebase_discover_concepts and type-member follow-ups to locate the declaration first")
                .contains("For follow-up-only capabilities, select only an issued FOLLOW_UP candidate whose targetCapability matches the tool");
    }
}
