package com.java.system.agent.model.action;

import com.java.system.agent.model.action.dto.ActionResponseType;
import com.java.system.agent.model.action.dto.AgentActionResponse;
import com.java.system.agent.model.action.dto.AnswerResponse;
import com.java.system.agent.model.action.dto.AnswerStatementResponse;
import com.java.system.agent.model.action.dto.ClarifyResponse;
import com.java.system.agent.runtime.domain.action.AnswerAction;
import com.java.system.agent.runtime.domain.action.ClarifyAction;
import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.answer.AnswerStatement;
import com.java.system.agent.runtime.domain.answer.ClaimId;
import com.java.system.agent.runtime.domain.answer.StatementId;
import com.java.system.agent.runtime.domain.answer.StatementType;
import com.java.system.agent.runtime.domain.handle.CandidateHandleRef;
import com.java.system.agent.runtime.domain.handle.EvidenceHandleRef;
import com.java.system.agent.runtime.domain.observation.ObservationId;
import com.java.system.agent.runtime.port.out.AgentActionProposal;
import com.java.system.agent.runtime.port.out.AgentPromptContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 將模型 envelope 轉為仍待 runtime 驗證的 action proposal
 */
public final class AgentActionResponseInterpreter {

    public static final String MALFORMED_DESCRIPTION = "MALFORMED_ACTION_RESPONSE";

    /**
     * 不可能的 DTO shape 一律轉為固定的可拒絕 proposal
     */
    public AgentActionProposal interpret(AgentActionResponse response, AgentPromptContext context) {
        try {
            Objects.requireNonNull(response, "action response must not be null");
            Objects.requireNonNull(context, "agent prompt context must not be null");
            Objects.requireNonNull(response.type(), "action response type must not be null");
            return switch (response.type()) {
                case ANSWER -> proposedAnswer(response, context);
                case CLARIFY -> proposedClarify(response, context);
            };
        } catch (RuntimeException exception) {
            return new AgentActionProposal.Malformed(MALFORMED_DESCRIPTION);
        }
    }

    private AgentActionProposal proposedAnswer(AgentActionResponse response, AgentPromptContext context) {
        requireOnly(response, ActionResponseType.ANSWER);
        AnswerResponse answer = Objects.requireNonNull(response.answer(), "answer response must not be null");
        List<AnswerStatement> statements = new ArrayList<>();
        for (AnswerStatementResponse statement : requiredList(answer.statements(), "answer statements")) {
            Objects.requireNonNull(statement, "answer statement must not be null");
            Optional<ClaimId> claimId = Optional.ofNullable(statement.claimId()).map(ClaimId::new);
            statements.add(new AnswerStatement(new StatementId(statement.statementId()), StatementType.valueOf(statement.type()),
                    statement.text(), claimId, evidenceHandles(statement.citationHandles()),
                    observationIds(statement.observationIds())));
        }
        return new AgentActionProposal.Proposed(new AnswerAction(new AnswerDocument(statements)));
    }

    private AgentActionProposal proposedClarify(AgentActionResponse response, AgentPromptContext context) {
        requireOnly(response, ActionResponseType.CLARIFY);
        ClarifyResponse clarify = Objects.requireNonNull(response.clarify(), "clarify response must not be null");
        return new AgentActionProposal.Proposed(new ClarifyAction(clarify.question(),
                candidateHandles(clarify.candidateHandles()), clarify.reason()));
    }

    private static void requireOnly(AgentActionResponse response, ActionResponseType expected) {
        boolean hasAnswer = Objects.nonNull(response.answer());
        boolean hasClarify = Objects.nonNull(response.clarify());
        if ((expected == ActionResponseType.ANSWER && (!hasAnswer || hasClarify))
                || (expected == ActionResponseType.CLARIFY && (hasAnswer || !hasClarify))) {
            throw new IllegalArgumentException("action response envelope is contradictory");
        }
    }

    private static List<CandidateHandleRef> candidateHandles(List<String> values) {
        List<CandidateHandleRef> handles = new ArrayList<>();
        for (String value : requiredList(values, "candidate handles")) {
            handles.add(new CandidateHandleRef(requiredText(value, "candidate handle")));
        }
        return List.copyOf(handles);
    }

    private static Set<EvidenceHandleRef> evidenceHandles(List<String> values) {
        Set<EvidenceHandleRef> handles = new LinkedHashSet<>();
        for (String value : requiredList(values, "citation handles")) {
            handles.add(new EvidenceHandleRef(requiredText(value, "evidence handle")));
        }
        return Set.copyOf(handles);
    }

    private static Set<ObservationId> observationIds(List<String> values) {
        Set<ObservationId> ids = new LinkedHashSet<>();
        for (String value : requiredList(values, "observation IDs")) {
            ids.add(new ObservationId(requiredText(value, "observation ID")));
        }
        return Set.copyOf(ids);
    }

    private static <T> List<T> requiredList(List<T> values, String description) {
        Objects.requireNonNull(values, description + " must not be null");
        return List.copyOf(values);
    }

    private static String requiredText(String value, String description) {
        Objects.requireNonNull(value, description + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be blank");
        }
        return value;
    }

}
