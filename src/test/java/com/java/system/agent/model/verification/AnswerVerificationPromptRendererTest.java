package com.java.system.agent.model.verification;

import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.ClaimId;
import com.java.system.agent.answering.domain.answer.StatementId;
import com.java.system.agent.answering.domain.answer.StatementType;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.conversation.ConversationTurn;
import com.java.system.agent.answering.domain.conversation.ConversationTurnType;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.evidence.ArtifactRef;
import com.java.system.agent.answering.domain.evidence.EvidenceRef;
import com.java.system.agent.answering.domain.evidence.IssuedEvidence;
import com.java.system.agent.answering.domain.evidence.SemanticTarget;
import com.java.system.agent.answering.domain.evidence.SemanticTargetKind;
import com.java.system.agent.answering.domain.handle.EvidenceHandle;
import com.java.system.agent.answering.domain.handle.EvidenceHandleRef;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.EvidenceCapabilityProvenance;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.out.AnswerVerificationContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AnswerVerificationPromptRenderer 的 participant 歷史輸出測試
 */
class AnswerVerificationPromptRendererTest {

    @Test
    void requires_complete_answers_to_cover_every_explicit_part_of_the_question() {
        assertThat(AnswerVerificationPromptRenderer.SYSTEM_INSTRUCTION)
                .contains("every explicit part of the current question")
                .contains("ACCEPTED_COMPLETE only when every requested part is answered")
                .contains("ACCEPTED_INCONCLUSIVE only when the document explicitly states unavoidable missing information")
                .contains("REJECTED when a requested part is omitted")
                .contains("An explicitly requested evidence type is itself a required part")
                .contains("Source text is not call-graph, implementation, or internal-reference evidence")
                .contains("both available and cited")
                .contains("unaddressedParts", "rejectionReasons");
    }

    @Test
    void renders_each_history_turn_with_its_stable_participant_label() {
        SessionHistory history = new SessionHistory(List.of(
                new ConversationTurn(new AnalysisRunId("run-1"), new ParticipantRef("slack", "U123456"),
                        "請查詢付款流程", "付款流程如下", ConversationTurnType.ANSWER),
                new ConversationTurn(new AnalysisRunId("run-2"), new ParticipantRef("slack", "U789012"),
                        "也包含退款流程", "退款流程如下", ConversationTurnType.ANSWER)));
        AnswerDocument document = new AnswerDocument(List.of(new AnswerStatement(
                new StatementId("statement-1"), StatementType.QUESTION, "付款流程", Optional.empty(), Set.of(), Set.of())));
        AnswerVerificationContext context = new AnswerVerificationContext(
                "請查詢付款流程", history, document, List.of(), List.of(), List.of(), List.of());

        String prompt = new AnswerVerificationPromptRenderer().render(context, "response contract");

        assertThat(prompt).contains("""
                Session history:
                participant[slack:U123456]: 請查詢付款流程
                assistant: 付款流程如下
                participant[slack:U789012]: 也包含退款流程
                assistant: 退款流程如下
                """);
        assertThat(prompt).doesNotContain("- user:");
    }

    @Test
    void renders_the_capability_that_produced_each_available_and_cited_evidence() {
        RepositoryId repositoryId = new RepositoryId("repository-1");
        RepositoryRevision revision = new RepositoryRevision("revision-1");
        HandleBinding binding = new HandleBinding(new AnalysisRunId("run-3"), new AnalysisAttemptId("attempt-1"),
                RevisionVector.empty().pin(repositoryId, revision));
        EvidenceHandle evidenceHandle = new EvidenceHandle("evidence-1", binding);
        IssuedEvidence evidence = new IssuedEvidence(evidenceHandle, new EvidenceRef(
                "semantic", repositoryId, revision,
                new SemanticTarget(SemanticTargetKind.SYMBOL, "Orders#create", Optional.empty()),
                "internalReference; target=Orders#create", List.of(), new ArtifactRef("digest-1")));
        CapabilityPolicy capability = new CapabilityPolicy(
                "codebase_find_internal_references", "v1", Set.of(CandidateKind.SEMANTIC_TARGET), 1, 1);
        AnswerDocument document = new AnswerDocument(List.of(new AnswerStatement(
                new StatementId("statement-1"), StatementType.FACT, "Orders#create is referenced",
                Optional.of(new ClaimId("claim-1")),
                Set.of(new EvidenceHandleRef(evidenceHandle.value())), Set.of())));
        AnswerVerificationContext context = new AnswerVerificationContext(
                "Find internal references", SessionHistory.empty(), document,
                List.of(evidence), List.of(), List.of(evidence), List.of(),
                List.of(new EvidenceCapabilityProvenance(evidenceHandle, capability)));

        String prompt = new AnswerVerificationPromptRenderer().render(context, "response contract");

        assertThat(prompt).contains(
                "- evidence-1 [evidenceType=codebase_find_internal_references@v1]: internalReference;");
        assertThat(prompt).contains(
                "- codebase_find_internal_references@v1: available=evidence-1; cited=evidence-1");
        assertThat(AnswerVerificationPromptRenderer.SYSTEM_INSTRUCTION)
                .contains("Evidence type metadata is authoritative")
                .contains("implementation evidence requires codebase_discover_method_implementations")
                .contains("internal-reference evidence requires codebase_find_internal_references");
    }
}
