package com.java.system.agent.answering.application.validation;

import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.action.AnswerAction;
import com.java.system.agent.answering.domain.action.ClarifyAction;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.handle.CandidateHandleRef;
import com.java.system.agent.answering.domain.handle.HandleBinding;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 不呼叫 port 且不改寫模型動作的確定性契約閘門
 */
public final class AgentActionValidator {

    private final AnswerDocumentValidator answerDocumentValidator;

    public AgentActionValidator() {
        answerDocumentValidator = new AnswerDocumentValidator();
    }

    public ActionValidation validate(AgentAction action, AgentValidationContext context) {
        Objects.requireNonNull(action, "agent action must not be null");
        Objects.requireNonNull(context, "agent validation context must not be null");
        if (action instanceof QueryAction query) {
            return validateQuery(query, context);
        }
        if (action instanceof AnswerAction answer) {
            return validateAnswer(answer, context);
        }
        if (action instanceof ClarifyAction clarify) {
            return validateNonQuery(clarify, clarify.candidates(), context);
        }
        return rejected(ActionRejectionCode.UNKNOWN_ACTION, action);
    }

    private ActionValidation validateAnswer(AnswerAction action, AgentValidationContext context) {
        try {
            answerDocumentValidator.validate(
                    action.document(), context.evidence(), context.observations(), context.currentBinding());
        } catch (AnswerDocumentContractException exception) {
            return rejected(exception.rejectionCode(), action);
        }
        return acceptIfAgentStepAvailable(action, List.of(), context);
    }

    private ActionValidation validateQuery(QueryAction action, AgentValidationContext context) {
        if (!sameAttempt(action.capability().binding(), context.currentBinding())) {
            return rejected(ActionRejectionCode.CROSS_ATTEMPT, action);
        }
        if (!hasCurrentRevision(action.capability(), context.currentBinding())) {
            return rejected(ActionRejectionCode.STALE_REVISION, action);
        }
        CapabilityPolicy capability = context.capabilities().get(action.capability());
        if (Objects.isNull(capability)) {
            return rejected(ActionRejectionCode.UNKNOWN_CAPABILITY, action);
        }
        Optional<List<IssuedCandidate>> resolved = resolveCandidates(action.candidates(), context);
        if (resolved.isEmpty()) {
            return rejected(ActionRejectionCode.UNKNOWN_CANDIDATE, action);
        }
        List<IssuedCandidate> candidates = resolved.orElseThrow();
        if (hasDuplicateCandidateValues(action.candidates())) {
            return rejected(ActionRejectionCode.DUPLICATE_CANDIDATE, action);
        }
        if (!allCandidatesMatchRevision(candidates, context)) {
            return rejected(ActionRejectionCode.STALE_REVISION, action);
        }
        if (!supportsCandidateKinds(candidates, capability)) {
            return rejected(ActionRejectionCode.INCOMPATIBLE_CANDIDATE_KIND, action);
        }
        if (!hasPermittedCardinality(candidates, capability)) {
            return rejected(ActionRejectionCode.CARDINALITY, action);
        }
        if (!context.budget().hasAgentStepRemaining() || !context.budget().hasQueryExecutionRemaining()) {
            return rejected(ActionRejectionCode.BUDGET_EXHAUSTED, action);
        }
        return new ActionValidation.Accepted(action, candidates);
    }

    private ActionValidation validateNonQuery(
            AgentAction action,
            List<CandidateHandleRef> candidateReferences,
            AgentValidationContext context) {
        Optional<List<IssuedCandidate>> resolved = resolveCandidates(candidateReferences, context);
        if (resolved.isEmpty()) {
            return rejected(ActionRejectionCode.UNKNOWN_CANDIDATE, action);
        }
        List<IssuedCandidate> candidates = resolved.orElseThrow();
        if (hasDuplicateCandidateValues(candidateReferences)) {
            return rejected(ActionRejectionCode.DUPLICATE_CANDIDATE, action);
        }
        if (!allCandidatesMatchRevision(candidates, context)) {
            return rejected(ActionRejectionCode.STALE_REVISION, action);
        }
        return acceptIfAgentStepAvailable(action, candidates, context);
    }

    private static ActionValidation acceptIfAgentStepAvailable(
            AgentAction action,
            List<IssuedCandidate> candidates,
            AgentValidationContext context) {
        if (!context.budget().hasAgentStepRemaining()) {
            return rejected(ActionRejectionCode.BUDGET_EXHAUSTED, action);
        }
        return new ActionValidation.Accepted(action, candidates);
    }

    private static Optional<List<IssuedCandidate>> resolveCandidates(
            List<CandidateHandleRef> references,
            AgentValidationContext context) {
        Map<String, IssuedCandidate> byValue = issuedCandidatesByValue(context.candidates());
        List<IssuedCandidate> resolved = references.stream().map(reference -> byValue.get(reference.value())).toList();
        if (resolved.stream().anyMatch(Objects::isNull)) {
            return Optional.empty();
        }
        return Optional.of(resolved);
    }

    private static Map<String, IssuedCandidate> issuedCandidatesByValue(Map<CandidateHandle, IssuedCandidate> candidates) {
        Map<String, IssuedCandidate> byValue = new HashMap<>();
        for (IssuedCandidate candidate : candidates.values()) {
            IssuedCandidate previous = byValue.put(candidate.handle().value(), candidate);
            if (Objects.nonNull(previous)) {
                throw new IllegalArgumentException("issued candidate values must be unique within an attempt");
            }
        }
        return Map.copyOf(byValue);
    }

    private static boolean hasCurrentRevision(CapabilityHandle capability, HandleBinding currentBinding) {
        return capability.binding().revisionVector().equals(currentBinding.revisionVector());
    }

    private static boolean supportsCandidateKinds(List<IssuedCandidate> candidates, CapabilityPolicy capability) {
        return candidates.stream().allMatch(candidate -> capability.acceptedCandidateKinds()
                .contains(candidate.candidate().kind()));
    }

    private static boolean hasPermittedCardinality(List<IssuedCandidate> candidates, CapabilityPolicy capability) {
        int size = candidates.size();
        return size >= capability.minimumCandidates() && size <= capability.maximumCandidates();
    }

    private static boolean hasDuplicateCandidateValues(List<CandidateHandleRef> candidates) {
        Set<String> seen = new HashSet<>();
        return candidates.stream().map(candidateHandleReference -> candidateHandleReference.value()).anyMatch(value -> !seen.add(value));
    }

    private static boolean allCandidatesMatchRevision(List<IssuedCandidate> candidates, AgentValidationContext context) {
        return candidates.stream().allMatch(candidate -> candidateMatchesRevision(candidate, context));
    }

    private static boolean candidateMatchesRevision(IssuedCandidate candidate, AgentValidationContext context) {
        if (!sameAttempt(candidate.handle().binding(), context.currentBinding())) {
            return false;
        }
        if (!candidate.handle().binding().revisionVector().equals(context.currentBinding().revisionVector())) {
            return false;
        }
        return candidate.candidate().repositoryRevision().isEmpty() || context.currentBinding().revisionVector()
                .matches(candidate.candidate().repositoryId(), candidate.candidate().repositoryRevision().orElseThrow());
    }

    private static boolean sameAttempt(HandleBinding left, HandleBinding right) {
        return left.runId().equals(right.runId()) && left.attemptId().equals(right.attemptId());
    }

    private static ActionValidation.Rejected rejected(ActionRejectionCode code, AgentAction action) {
        return new ActionValidation.Rejected(code, code.name(), action);
    }
}
