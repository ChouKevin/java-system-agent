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
 * AnswerVerificationPromptRenderer 的 participant 歷史輸出測試
 */
class AnswerVerificationPromptRendererTest {

    @Test
    void projects_exactly_the_catalog_verification_context_keys_with_typed_fact_ids() {
        SessionHistory history = new SessionHistory(List.of(
                new ConversationTurn(new AnalysisRunId("run-1"), new ParticipantRef("slack", "U123456"),
                        "請查詢付款流程", "付款流程如下", ConversationTurnType.ANSWER),
                new ConversationTurn(new AnalysisRunId("run-2"), new ParticipantRef("slack", "U789012"),
                        "也包含退款流程", "退款流程如下", ConversationTurnType.ANSWER)));
        AnswerDocument document = new AnswerDocument(List.of(new AnswerStatement(
                new StatementId("statement-1"), StatementType.QUESTION, "付款流程", Optional.empty(), Set.of(), Set.of())));
        AnswerVerificationContext context = new AnswerVerificationContext(
                "請查詢付款流程", history, document, List.of(), List.of(), List.of(), List.of());

        Map<String, Object> projection = renderer().project(context, "response contract");

        assertThat(projection).containsOnlyKeys("currentQuestion", "sessionHistory", "proposedDocument",
                "availableEvidence", "availableObservations", "citedEvidence", "evidenceTypeCoverage",
                "referencedObservations", "requiredFactStatementVerdicts", "responseContract");
        assertThat((String) projection.get("sessionHistory")).containsSubsequence("participant[slack:U123456]",
                "assistant: 付款流程如下", "participant[slack:U789012]", "assistant: 退款流程如下");
        assertThat((String) projection.get("requiredFactStatementVerdicts"))
                .isEqualTo("- none; statementVerdicts must be []\n");
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

        Map<String, Object> projection = renderer().project(context, "response contract");

        assertThat((String) projection.get("availableEvidence")).contains(
                "- evidence-1 [evidenceType=codebase_find_internal_references@v1]: internalReference;");
        assertThat((String) projection.get("evidenceTypeCoverage")).contains(
                "- codebase_find_internal_references@v1: available=evidence-1; cited=evidence-1");
        assertThat((String) projection.get("requiredFactStatementVerdicts")).isEqualTo("- statement-1\n");
    }

    @Test
    void renders_the_complete_projection_through_the_injected_catalog() {
        PromptResourceCatalog catalog = mock(PromptResourceCatalog.class);
        AnswerVerificationPromptRenderer renderer = new AnswerVerificationPromptRenderer(catalog);
        AnswerVerificationContext context = new AnswerVerificationContext("question", SessionHistory.empty(),
                new AnswerDocument(List.of(new AnswerStatement(new StatementId("statement-question"),
                        StatementType.QUESTION, "question", Optional.empty(), Set.of(), Set.of()))),
                List.of(), List.of(), List.of(), List.of());
        when(catalog.renderVerificationContext(anyMap())).thenReturn("resource-rendered-context");

        String rendered = renderer.render(context, "response contract");

        assertThat(rendered).isEqualTo("resource-rendered-context");
        verify(catalog).renderVerificationContext(renderer.project(context, "response contract"));
    }

    private static AnswerVerificationPromptRenderer renderer() {
        return new AnswerVerificationPromptRenderer(mock(PromptResourceCatalog.class));
    }
}
