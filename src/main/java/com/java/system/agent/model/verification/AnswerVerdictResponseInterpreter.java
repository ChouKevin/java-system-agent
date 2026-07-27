package com.java.system.agent.model.verification;

import com.java.system.agent.model.verification.dto.AnswerVerdictResponse;
import com.java.system.agent.model.verification.dto.StatementVerdictResponse;
import com.java.system.agent.runtime.domain.answer.AnswerDisposition;
import com.java.system.agent.runtime.domain.answer.AnswerVerdict;
import com.java.system.agent.runtime.domain.answer.StatementId;
import com.java.system.agent.runtime.domain.answer.StatementType;
import com.java.system.agent.runtime.domain.answer.StatementVerdict;
import com.java.system.agent.runtime.domain.answer.StatementVerdictStatus;
import com.java.system.agent.runtime.port.out.AnswerVerificationContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 將 verifier DTO 轉為 runtime verdict 並檢查文件的精確 statement 集合
 */
public final class AnswerVerdictResponseInterpreter {

    /**
     * 任何遺漏、重複或外來 statement ID 都拒絕為不可用的結構輸出
     */
    public AnswerVerdict interpret(AnswerVerdictResponse response, AnswerVerificationContext context) {
        Objects.requireNonNull(response, "answer verdict response must not be null");
        Objects.requireNonNull(context, "answer verification context must not be null");
        List<StatementVerdict> statementVerdicts = new ArrayList<>();
        Set<StatementId> responseIds = new LinkedHashSet<>();
        for (StatementVerdictResponse statement : requiredList(response.statementVerdicts(), "statement verdicts")) {
            Objects.requireNonNull(statement, "statement verdict must not be null");
            StatementId statementId = new StatementId(statement.statementId());
            if (!responseIds.add(statementId)) {
                throw new IllegalArgumentException("statement verdict IDs must be unique");
            }
            statementVerdicts.add(new StatementVerdict(statementId, StatementVerdictStatus.valueOf(statement.status()),
                    statement.description()));
        }
        Set<StatementId> factStatementIds = new LinkedHashSet<>();
        context.document().statements().stream()
                .filter(statement -> statement.type() == StatementType.FACT)
                .forEach(statement -> factStatementIds.add(statement.statementId()));
        if (!factStatementIds.equals(responseIds)) {
            throw new IllegalArgumentException("statement verdict IDs must exactly match fact statements");
        }
        return new AnswerVerdict(AnswerDisposition.valueOf(response.disposition()), statementVerdicts,
                requiredList(response.unaddressedParts(), "unaddressed parts"),
                requiredList(response.blockingUncertainties(), "blocking uncertainties"),
                requiredList(response.rejectionReasons(), "rejection reasons"));
    }

    private static <T> List<T> requiredList(List<T> values, String name) {
        Objects.requireNonNull(values, name + " must not be null");
        return List.copyOf(values);
    }
}
