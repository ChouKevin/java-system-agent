package com.java.system.agent.runtime.application.validation;

import com.java.system.agent.runtime.domain.answer.AnswerDisposition;
import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.answer.AnswerStatement;
import com.java.system.agent.runtime.domain.answer.AnswerVerdict;
import com.java.system.agent.runtime.domain.answer.ClaimId;
import com.java.system.agent.runtime.domain.answer.StatementId;
import com.java.system.agent.runtime.domain.answer.StatementType;
import com.java.system.agent.runtime.domain.answer.StatementVerdict;
import com.java.system.agent.runtime.domain.answer.StatementVerdictStatus;
import com.java.system.agent.runtime.domain.handle.EvidenceHandle;
import com.java.system.agent.runtime.domain.handle.EvidenceHandleRef;
import com.java.system.agent.runtime.domain.handle.HandleBinding;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnswerVerdictValidatorTest {

    private final AnswerVerdictValidator validator = new AnswerVerdictValidator();

    @Test
    void should_reject_missing_fact_verdict() {
        AnswerDocumentValidation validation = validation(List.of(fact("fact")));

        assertThatThrownBy(() -> validator.validate(validation, new AnswerVerdict(AnswerDisposition.ACCEPTED_COMPLETE,
                List.of(), List.of(), List.of(), List.of())))
                .isInstanceOf(VerifierContractException.class);
    }

    @Test
    void should_reject_unknown_duplicate_and_non_fact_verdict_identifiers() {
        AnswerStatement uncertainty = new AnswerStatement(new StatementId("uncertain"), StatementType.UNCERTAINTY,
                "Call unresolved", Optional.empty(), Set.of(), Set.of());
        AnswerDocumentValidation validation = validation(List.of(fact("fact"), uncertainty));

        assertThatThrownBy(() -> validator.validate(validation,
                verdict(AnswerDisposition.REJECTED, "unknown", StatementVerdictStatus.UNSUPPORTED)))
                .isInstanceOf(VerifierContractException.class);
        assertThatThrownBy(() -> validator.validate(validation, new AnswerVerdict(AnswerDisposition.REJECTED, List.of(
                new StatementVerdict(new StatementId("fact"), StatementVerdictStatus.UNSUPPORTED, "First result"),
                new StatementVerdict(new StatementId("fact"), StatementVerdictStatus.UNSUPPORTED, "Second result")),
                List.of(), List.of(), List.of())))
                .isInstanceOf(VerifierContractException.class);
        assertThatThrownBy(() -> validator.validate(validation,
                verdict(AnswerDisposition.REJECTED, "uncertain", StatementVerdictStatus.UNSUPPORTED)))
                .isInstanceOf(VerifierContractException.class);
    }

    @Test
    void should_reject_accepted_disposition_for_unsupported_fact() {
        AnswerDocumentValidation validation = validation(List.of(fact("fact")));
        AnswerVerdict verdict = verdict(AnswerDisposition.ACCEPTED_INCONCLUSIVE, "fact", StatementVerdictStatus.UNSUPPORTED);

        assertThatThrownBy(() -> validator.validate(validation, verdict)).isInstanceOf(VerifierContractException.class);
    }

    @Test
    void should_not_infer_blocking_from_a_non_fact_statement() {
        AnswerStatement uncertainty = new AnswerStatement(new StatementId("uncertain"), StatementType.UNCERTAINTY,
                "Call unresolved", Optional.empty(), Set.of(), Set.of());
        AnswerDocumentValidation validation = validation(List.of(fact("fact"), uncertainty));

        assertThatCode(() -> validator.validate(validation,
                verdict(AnswerDisposition.ACCEPTED_COMPLETE, "fact", StatementVerdictStatus.SUPPORTED)))
                .doesNotThrowAnyException();
    }

    @Test
    void should_reject_inconclusive_answer_when_any_fact_is_unsupported() {
        AnswerDocumentValidation validation = validation(List.of(fact("fact")));

        assertThatThrownBy(() -> validator.validate(validation,
                verdict(AnswerDisposition.ACCEPTED_INCONCLUSIVE, "fact", StatementVerdictStatus.UNSUPPORTED)))
                .isInstanceOf(VerifierContractException.class);
    }

    @Test
    void should_allow_semantically_valid_rejected_verdict() {
        AnswerDocumentValidation validation = validation(List.of(fact("fact")));

        assertThatCode(() -> validator.validate(validation,
                verdict(AnswerDisposition.REJECTED, "fact", StatementVerdictStatus.UNSUPPORTED)))
                .doesNotThrowAnyException();
    }

    private static AnswerDocumentValidation validation(List<AnswerStatement> statements) {
        return new AnswerDocumentValidation(new AnswerDocument(statements), Map.of(), Map.of());
    }

    private static AnswerStatement fact(String id) {
        HandleBinding binding = new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"), RevisionVector.empty());
        EvidenceHandle evidence = new EvidenceHandle("evidence-" + id, binding);
        return new AnswerStatement(new StatementId(id), StatementType.FACT, "Fact " + id,
                Optional.of(new ClaimId("claim-" + id)), Set.of(new EvidenceHandleRef(evidence.value())), Set.of());
    }

    private static AnswerVerdict verdict(AnswerDisposition disposition, String id, StatementVerdictStatus status) {
        return new AnswerVerdict(disposition, List.of(new StatementVerdict(new StatementId(id), status,
                "Verifier result")), List.of(), List.of(), List.of());
    }
}
