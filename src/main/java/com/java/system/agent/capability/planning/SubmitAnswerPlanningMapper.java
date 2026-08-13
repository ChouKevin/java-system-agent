package com.java.system.agent.capability.planning;

import com.java.system.agent.answering.domain.action.AnswerAction;
import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.ClaimId;
import com.java.system.agent.answering.domain.answer.StatementId;
import com.java.system.agent.answering.domain.handle.EvidenceHandleRef;
import com.java.system.agent.answering.domain.observation.ObservationId;
import com.java.system.agent.answering.domain.plan.InformationNeedId;
import com.java.system.agent.answering.domain.plan.NeedResolution;

import java.util.ArrayList;
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
        List<AnswerStatementPlanningInput> statementInputs = new ArrayList<>();
        statementInputs.addAll(input.facts());
        statementInputs.addAll(input.uncertainties());
        statementInputs.addAll(input.limitations());
        statementInputs.addAll(input.questions());
        List<AnswerStatement> statements = statementInputs.stream().map(this::statement).toList();
        List<NeedResolution> resolutions;
        try {
            resolutions = input.resolutions().stream().map(this::resolution).toList();
        } catch (IllegalArgumentException exception) {
            throw resolutionRejected(exception);
        }
        return new AnswerAction(new AnswerDocument(statements), resolutions);
    }

    private NeedResolution resolution(NeedResolutionPlanningInput input) {
        Set<EvidenceHandleRef> evidence = input.evidenceHandles().stream().map(EvidenceHandleRef::new)
                .collect(Collectors.toUnmodifiableSet());
        Set<ObservationId> observations = input.observationIds().stream().map(ObservationId::new)
                .collect(Collectors.toUnmodifiableSet());
        return new NeedResolution(new InformationNeedId(input.needId()), input.status(), evidence, observations);
    }

    private AnswerStatement statement(AnswerStatementPlanningInput input) {
        Optional<ClaimId> claimId = switch (input) {
            case AnswerStatementPlanningInput.Fact fact -> Optional.of(new ClaimId(fact.claimId()));
            case AnswerStatementPlanningInput.Uncertainty ignored -> Optional.empty();
            case AnswerStatementPlanningInput.Limitation ignored -> Optional.empty();
            case AnswerStatementPlanningInput.Question ignored -> Optional.empty();
        };
        Set<EvidenceHandleRef> citations = input.citationHandles().stream().map(EvidenceHandleRef::new)
                .collect(Collectors.toUnmodifiableSet());
        Set<ObservationId> observationIds = input.observationIds().stream().map(ObservationId::new)
                .collect(Collectors.toUnmodifiableSet());
        return new AnswerStatement(new StatementId(input.statementId()), input.type(), input.text(), claimId, citations,
                observationIds);
    }

    private static PlanningToolInputException resolutionRejected(IllegalArgumentException exception) {
        return new PlanningToolInputException("reason=ANSWER_RESOLUTION_CONTRACT", exception);
    }
}
