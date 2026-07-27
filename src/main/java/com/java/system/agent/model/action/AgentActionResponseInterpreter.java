package com.java.system.agent.model.action;

import com.java.system.agent.model.action.dto.ActionResponseType;
import com.java.system.agent.model.action.dto.AgentActionResponse;
import com.java.system.agent.model.action.dto.AnswerResponse;
import com.java.system.agent.model.action.dto.AnswerStatementResponse;
import com.java.system.agent.model.action.dto.ClarifyResponse;
import com.java.system.agent.model.action.dto.QueryResponse;
import com.java.system.agent.runtime.domain.action.AnswerAction;
import com.java.system.agent.runtime.domain.action.ClarifyAction;
import com.java.system.agent.runtime.domain.action.QueryAction;
import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.answer.AnswerStatement;
import com.java.system.agent.runtime.domain.answer.ClaimId;
import com.java.system.agent.runtime.domain.answer.StatementId;
import com.java.system.agent.runtime.domain.answer.StatementType;
import com.java.system.agent.runtime.domain.candidate.CandidateKind;
import com.java.system.agent.runtime.domain.handle.CapabilityHandle;
import com.java.system.agent.runtime.domain.handle.CandidateHandle;
import com.java.system.agent.runtime.domain.handle.EvidenceHandle;
import com.java.system.agent.runtime.domain.handle.HandleBinding;
import com.java.system.agent.runtime.domain.observation.ObservationId;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
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
                case QUERY -> proposedQuery(response, context);
                case ANSWER -> proposedAnswer(response, context);
                case CLARIFY -> proposedClarify(response, context);
            };
        } catch (RuntimeException exception) {
            return new AgentActionProposal.Malformed(MALFORMED_DESCRIPTION);
        }
    }

    private AgentActionProposal proposedQuery(AgentActionResponse response, AgentPromptContext context) {
        requireOnly(response, ActionResponseType.QUERY);
        QueryResponse query = Objects.requireNonNull(response.query(), "query response must not be null");
        HandleBinding binding = binding(context);
        CapabilityHandle capability = capabilityHandle(query.capabilityHandle(), context, binding);
        List<CandidateHandle> candidates = candidateHandles(query.candidateHandles(), context, binding);
        return new AgentActionProposal.Proposed(new QueryAction(capability, candidates, query.questionToResolve(),
                immutableArguments(query.arguments()), query.rationale()));
    }

    private AgentActionProposal proposedAnswer(AgentActionResponse response, AgentPromptContext context) {
        requireOnly(response, ActionResponseType.ANSWER);
        AnswerResponse answer = Objects.requireNonNull(response.answer(), "answer response must not be null");
        HandleBinding binding = binding(context);
        List<AnswerStatement> statements = new ArrayList<>();
        for (AnswerStatementResponse statement : requiredList(answer.statements(), "answer statements")) {
            Objects.requireNonNull(statement, "answer statement must not be null");
            Optional<ClaimId> claimId = Optional.ofNullable(statement.claimId()).map(ClaimId::new);
            statements.add(new AnswerStatement(new StatementId(statement.statementId()), StatementType.valueOf(statement.type()),
                    statement.text(), claimId, evidenceHandles(statement.citationHandles(), context, binding),
                    observationIds(statement.observationIds())));
        }
        return new AgentActionProposal.Proposed(new AnswerAction(new AnswerDocument(statements)));
    }

    private AgentActionProposal proposedClarify(AgentActionResponse response, AgentPromptContext context) {
        requireOnly(response, ActionResponseType.CLARIFY);
        ClarifyResponse clarify = Objects.requireNonNull(response.clarify(), "clarify response must not be null");
        return new AgentActionProposal.Proposed(new ClarifyAction(clarify.question(),
                candidateHandles(clarify.candidateHandles(), context, binding(context)), clarify.reason()));
    }

    private static void requireOnly(AgentActionResponse response, ActionResponseType expected) {
        boolean hasQuery = Objects.nonNull(response.query());
        boolean hasAnswer = Objects.nonNull(response.answer());
        boolean hasClarify = Objects.nonNull(response.clarify());
        if ((expected == ActionResponseType.QUERY && (!hasQuery || hasAnswer || hasClarify))
                || (expected == ActionResponseType.ANSWER && (hasQuery || !hasAnswer || hasClarify))
                || (expected == ActionResponseType.CLARIFY && (hasQuery || hasAnswer || !hasClarify))) {
            throw new IllegalArgumentException("action response envelope is contradictory");
        }
    }

    private static CapabilityHandle capabilityHandle(String value, AgentPromptContext context, HandleBinding binding) {
        String requiredValue = requiredText(value, "capability handle");
        for (CapabilityHandle handle : context.issuedCapabilities().keySet()) {
            if (handle.value().equals(requiredValue)) {
                return handle;
            }
        }
        return new CapabilityHandle(requiredValue, binding);
    }

    private static List<CandidateHandle> candidateHandles(List<String> values, AgentPromptContext context,
                                                           HandleBinding binding) {
        List<CandidateHandle> handles = new ArrayList<>();
        for (String value : requiredList(values, "candidate handles")) {
            handles.add(candidateHandle(value, context, binding));
        }
        return List.copyOf(handles);
    }

    private static CandidateHandle candidateHandle(String value, AgentPromptContext context, HandleBinding binding) {
        String requiredValue = requiredText(value, "candidate handle");
        for (CandidateHandle handle : context.issuedCandidates().keySet()) {
            if (handle.value().equals(requiredValue)) {
                return handle;
            }
        }
        return new CandidateHandle(requiredValue, binding, CandidateKind.REPOSITORY);
    }

    private static Set<EvidenceHandle> evidenceHandles(List<String> values, AgentPromptContext context,
                                                        HandleBinding binding) {
        Set<EvidenceHandle> handles = new LinkedHashSet<>();
        for (String value : requiredList(values, "citation handles")) {
            handles.add(evidenceHandle(value, context, binding));
        }
        return Set.copyOf(handles);
    }

    private static EvidenceHandle evidenceHandle(String value, AgentPromptContext context, HandleBinding binding) {
        String requiredValue = requiredText(value, "evidence handle");
        for (EvidenceHandle handle : context.issuedEvidence().keySet()) {
            if (handle.value().equals(requiredValue)) {
                return handle;
            }
        }
        return new EvidenceHandle(requiredValue, binding);
    }

    private static Set<ObservationId> observationIds(List<String> values) {
        Set<ObservationId> ids = new LinkedHashSet<>();
        for (String value : requiredList(values, "observation IDs")) {
            ids.add(new ObservationId(requiredText(value, "observation ID")));
        }
        return Set.copyOf(ids);
    }

    private static Map<String, String> immutableArguments(Map<String, String> arguments) {
        Objects.requireNonNull(arguments, "query arguments must not be null");
        for (Map.Entry<String, String> entry : arguments.entrySet()) {
            requiredText(entry.getKey(), "query argument key");
            requiredText(entry.getValue(), "query argument value");
        }
        return Map.copyOf(arguments);
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

    private static HandleBinding binding(AgentPromptContext context) {
        return new HandleBinding(context.runId(), context.attemptId(), RevisionVector.empty());
    }
}
