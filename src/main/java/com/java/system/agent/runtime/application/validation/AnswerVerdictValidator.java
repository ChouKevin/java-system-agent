package com.java.system.agent.runtime.application.validation;

import com.java.system.agent.runtime.domain.answer.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** 驗證 verdict 與回答文件間一對一且可接受的關係 */
public final class AnswerVerdictValidator {
    public void validate(AnswerDocumentValidation validation, AnswerVerdict verdict) {
        if (Objects.isNull(validation) || Objects.isNull(verdict)) {
            throw new VerifierContractException("answer validation and verdict must be present");
        }
        Map<StatementId, AnswerStatement> statements = new HashMap<>();
        for (AnswerStatement statement : validation.document().statements()) statements.put(statement.statementId(), statement);
        Map<StatementId, StatementVerdict> verdicts = new HashMap<>();
        for (StatementVerdict value : verdict.statementVerdicts()) {
            StatementVerdict prior = verdicts.putIfAbsent(value.statementId(), value);
            if (Objects.nonNull(prior)) throw new VerifierContractException("statement verdict IDs must be unique");
            AnswerStatement statement = statements.get(value.statementId());
            if (Objects.isNull(statement)) throw new VerifierContractException("statement verdict references an unknown statement");
            if (statement.type() != StatementType.FACT) throw new VerifierContractException("non-fact statements must not have verdicts");
        }
        for (AnswerStatement statement : statements.values()) if (statement.type() == StatementType.FACT && !verdicts.containsKey(statement.statementId())) throw new VerifierContractException("fact statements require exactly one verdict");
        boolean unsupported = verdicts.values().stream().anyMatch(value -> value.status() == StatementVerdictStatus.UNSUPPORTED);
        if (unsupported && verdict.disposition() != AnswerDisposition.REJECTED) throw new VerifierContractException("unsupported facts require a rejected answer");
        if (verdict.disposition() == AnswerDisposition.ACCEPTED_INCONCLUSIVE && unsupported) {
            throw new VerifierContractException("inconclusive answers require every fact to be supported");
        }
        if (verdict.disposition() == AnswerDisposition.ACCEPTED_COMPLETE
                && (!verdict.unaddressedParts().isEmpty() || !verdict.blockingUncertainties().isEmpty())) {
            throw new VerifierContractException("complete answers must not retain explicit gaps or uncertainties");
        }
    }
}
