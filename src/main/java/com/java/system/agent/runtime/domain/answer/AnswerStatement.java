package com.java.system.agent.runtime.domain.answer;

import com.java.system.agent.runtime.domain.handle.EvidenceHandleRef;
import com.java.system.agent.runtime.domain.observation.ObservationId;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 模型回答文件中可被獨立驗證的一段文字，citation 值必須由 runtime 依本輪 issued 證據解析
 */
public record AnswerStatement(StatementId statementId, StatementType type, String text, Optional<ClaimId> claimId,
        Set<EvidenceHandleRef> citations, Set<ObservationId> observationIds) {
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
}
