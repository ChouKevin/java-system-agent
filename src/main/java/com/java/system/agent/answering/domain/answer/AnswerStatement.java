package com.java.system.agent.answering.domain.answer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.java.system.agent.answering.domain.handle.EvidenceHandleRef;
import com.java.system.agent.answering.domain.observation.ObservationId;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 模型回答文件中可被獨立驗證的一段文字，citation 值必須由 answering 依本輪 issued 證據解析
 */
public record AnswerStatement(StatementId statementId, StatementType type, String text, Optional<ClaimId> claimId,
        Set<EvidenceHandleRef> citations, Set<ObservationId> observationIds) {

    @JsonCreator
    public AnswerStatement(
            @JsonProperty("statement_id") StatementId statementId,
            @JsonProperty("type") StatementType type,
            @JsonProperty("text") String text,
            @JsonProperty("claim_id") ClaimId claimId,
            @JsonProperty("citations") List<EvidenceHandleRef> citations,
            @JsonProperty("observation_ids") List<ObservationId> observationIds) {
        this(statementId, type, text, Optional.ofNullable(claimId),
                uniqueValues(citations, "answer statement citations"),
                uniqueValues(observationIds, "answer statement observation IDs"));
    }

    public AnswerStatement {
        Objects.requireNonNull(statementId, "answer statement ID must not be null");
        Objects.requireNonNull(type, "answer statement type must not be null");
        Objects.requireNonNull(text, "answer statement text must not be null");
        Objects.requireNonNull(claimId, "answer statement claim ID must not be null");
        Objects.requireNonNull(citations, "answer statement citations must not be null");
        Objects.requireNonNull(observationIds, "answer statement observation IDs must not be null");
        if (text.isBlank()) throw new IllegalArgumentException("answer statement text must not be blank");
        citations = Set.copyOf(citations); observationIds = Set.copyOf(observationIds);
        if (type == StatementType.FACT && (claimId.isEmpty() || citations.isEmpty())) throw new IllegalArgumentException("fact statements require a claim ID and citation");
        if (type != StatementType.FACT && claimId.isPresent()) throw new IllegalArgumentException("non-fact statements must not declare a claim ID");
    }

    private static <T> Set<T> uniqueValues(List<T> values, String description) {
        Objects.requireNonNull(values, description + " must not be null");
        Set<T> uniqueValues = new LinkedHashSet<>();
        for (T value : values) {
            if (!uniqueValues.add(Objects.requireNonNull(value, description + " must not contain null"))) {
                throw new IllegalArgumentException(description + " must not contain duplicates");
            }
        }
        return uniqueValues;
    }
}
