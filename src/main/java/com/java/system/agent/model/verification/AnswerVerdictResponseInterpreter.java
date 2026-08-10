package com.java.system.agent.model.verification;

import com.java.system.agent.model.verification.dto.AnswerVerdictResponse;
import com.java.system.agent.model.verification.dto.StatementVerdictResponse;
import com.java.system.agent.answering.domain.answer.AnswerVerdict;
import com.java.system.agent.answering.domain.answer.StatementId;
import com.java.system.agent.answering.domain.answer.StatementType;
import com.java.system.agent.answering.domain.answer.StatementVerdict;
import com.java.system.agent.answering.port.out.AnswerVerificationContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 將 verifier DTO 轉為 answering verdict 並檢查文件的精確 statement 集合
 */
public final class AnswerVerdictResponseInterpreter {

    private static final Logger LOGGER = Logger.getLogger(AnswerVerdictResponseInterpreter.class.getName());
    private final ExplicitEvidenceCoveragePolicy evidenceCoveragePolicy;

    public AnswerVerdictResponseInterpreter(ExplicitEvidenceCoveragePolicy evidenceCoveragePolicy) {
        this.evidenceCoveragePolicy = Objects.requireNonNull(evidenceCoveragePolicy,
                "explicit evidence coverage policy must not be null");
    }

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
            statementVerdicts.add(new StatementVerdict(statementId, statement.status(), statement.description()));
        }
        Set<StatementId> factStatementIds = new LinkedHashSet<>();
        context.document().statements().stream()
                .filter(statement -> statement.type() == StatementType.FACT)
                .forEach(statement -> factStatementIds.add(statement.statementId()));
        if (!factStatementIds.equals(responseIds)) {
            long missingCount = factStatementIds.stream().filter(statementId -> !responseIds.contains(statementId)).count();
            long unexpectedCount = responseIds.stream().filter(statementId -> !factStatementIds.contains(statementId)).count();
            LOGGER.log(Level.WARNING,
                    "answer verifier contract mismatch expectedFactStatementCount={0} "
                            + "returnedStatementVerdictCount={1} missingFactStatementVerdictCount={2} "
                            + "unexpectedStatementVerdictCount={3}",
                    new Object[]{factStatementIds.size(), responseIds.size(), missingCount, unexpectedCount});
            throw new IllegalArgumentException("statement verdict IDs must exactly match fact statements");
        }
        AnswerVerdict verdict = new AnswerVerdict(response.disposition(), statementVerdicts,
                requiredList(response.unaddressedParts(), "unaddressed parts"),
                requiredList(response.blockingUncertainties(), "blocking uncertainties"),
                requiredList(response.rejectionReasons(), "rejection reasons"));
        return evidenceCoveragePolicy.enforce(context, verdict);
    }

    private static <T> List<T> requiredList(List<T> values, String name) {
        Objects.requireNonNull(values, name + " must not be null");
        return List.copyOf(values);
    }
}
