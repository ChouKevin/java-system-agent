package com.java.system.agent.runtime.application.validation;

import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.answer.AnswerStatement;
import com.java.system.agent.runtime.domain.answer.ClaimId;
import com.java.system.agent.runtime.domain.answer.StatementId;
import com.java.system.agent.runtime.domain.answer.StatementType;
import com.java.system.agent.runtime.domain.evidence.ArtifactRef;
import com.java.system.agent.runtime.domain.evidence.EvidenceRef;
import com.java.system.agent.runtime.domain.evidence.IssuedEvidence;
import com.java.system.agent.runtime.domain.evidence.SemanticTarget;
import com.java.system.agent.runtime.domain.evidence.SemanticTargetKind;
import com.java.system.agent.runtime.domain.handle.EvidenceHandle;
import com.java.system.agent.runtime.domain.handle.EvidenceHandleRef;
import com.java.system.agent.runtime.domain.handle.HandleBinding;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型 raw evidence handle value 的 runtime 驗證測試
 */
class AnswerDocumentValidatorTest {

    private final AnswerDocumentValidator validator = new AnswerDocumentValidator();

    @Test
    void resolves_evidence_value_before_using_the_current_binding_and_revision() {
        HandleBinding binding = binding("attempt-1", "rev-1");
        EvidenceHandle reissued = new EvidenceHandle("evidence-1", binding);
        AnswerDocument document = document(new EvidenceHandleRef("evidence-1"));

        AnswerDocumentValidation validation = validator.validate(document, Map.of(reissued, issued(reissued)), Map.of(), binding);

        assertThat(validation.citedEvidence()).containsOnlyKeys(new EvidenceHandleRef("evidence-1"));
        assertThat(validation.citedEvidence().get(new EvidenceHandleRef("evidence-1"))).isEqualTo(issued(reissued));
    }

    @Test
    void rejects_an_unissued_evidence_value() {
        HandleBinding binding = binding("attempt-1", "rev-1");

        assertThatThrownBy(() -> validator.validate(document(new EvidenceHandleRef("unknown")), Map.of(), Map.of(), binding))
                .isInstanceOf(AnswerDocumentContractException.class)
                .hasMessageContaining("unknown evidence");
    }

    @Test
    void rejects_evidence_value_when_the_current_issued_handle_is_from_another_attempt() {
        HandleBinding current = binding("attempt-1", "rev-1");
        EvidenceHandle foreign = new EvidenceHandle("evidence-1", binding("attempt-2", "rev-1"));

        assertThatThrownBy(() -> validator.validate(document(new EvidenceHandleRef("evidence-1")),
                Map.of(foreign, issued(foreign)), Map.of(), current))
                .isInstanceOf(AnswerDocumentContractException.class)
                .hasMessageContaining("another attempt");
    }

    private static AnswerDocument document(EvidenceHandleRef evidence) {
        return new AnswerDocument(List.of(new AnswerStatement(new StatementId("fact"), StatementType.FACT,
                "Orders are created", Optional.of(new ClaimId("claim")), Set.of(evidence), Set.of())));
    }

    private static HandleBinding binding(String attempt, String revision) {
        RepositoryId repository = new RepositoryId("repo-1");
        return new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId(attempt),
                RevisionVector.empty().pin(repository, new RepositoryRevision(revision)));
    }

    private static IssuedEvidence issued(EvidenceHandle handle) {
        return new IssuedEvidence(handle, new EvidenceRef("semantic", new RepositoryId("repo-1"),
                new RepositoryRevision("rev-1"), new SemanticTarget(SemanticTargetKind.SYMBOL, "Orders#create", Optional.empty()),
                "Evidence", List.of(), new ArtifactRef("digest")));
    }
}
