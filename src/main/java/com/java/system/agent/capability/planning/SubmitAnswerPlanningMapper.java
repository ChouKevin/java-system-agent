package com.java.system.agent.capability.planning;

import com.java.system.agent.answering.domain.action.AnswerAction;
import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.ClaimId;
import com.java.system.agent.answering.domain.answer.StatementId;
import com.java.system.agent.answering.domain.handle.EvidenceHandleRef;
import com.java.system.agent.answering.domain.observation.ObservationId;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 將 ANSWER planning input 轉為保留原始 opaque reference 值的未驗證 action
 */
public final class SubmitAnswerPlanningMapper implements Function<SubmitAnswerPlanningInput, AnswerAction> {

    @Override
    public AnswerAction apply(SubmitAnswerPlanningInput input) {
        List<AnswerStatement> statements = input.statements().stream().map(this::statement).toList();
        return new AnswerAction(new AnswerDocument(statements));
    }

    private AnswerStatement statement(AnswerStatementPlanningInput input) {
        Optional<ClaimId> claimId = Optional.ofNullable(input.claimId()).map(ClaimId::new);
        Set<EvidenceHandleRef> citations = input.citationHandles().stream().map(EvidenceHandleRef::new)
                .collect(Collectors.toUnmodifiableSet());
        Set<ObservationId> observationIds = input.observationIds().stream().map(ObservationId::new)
                .collect(Collectors.toUnmodifiableSet());
        return new AnswerStatement(new StatementId(input.statementId()), input.type(), input.text(), claimId, citations,
                observationIds);
    }
}
