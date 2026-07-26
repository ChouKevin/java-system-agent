package com.java.system.agent.runtime.application.validation;

import com.java.system.agent.runtime.domain.action.AgentAction;
import com.java.system.agent.runtime.domain.action.AnswerAction;
import com.java.system.agent.runtime.domain.action.ClarifyAction;
import com.java.system.agent.runtime.domain.action.QueryAction;
import com.java.system.agent.runtime.domain.capability.CapabilityDescriptor;
import com.java.system.agent.runtime.domain.capability.CapabilityQueryContractException;
import com.java.system.agent.runtime.domain.candidate.IssuedCandidate;
import com.java.system.agent.runtime.domain.handle.CapabilityHandle;
import com.java.system.agent.runtime.domain.handle.CandidateHandle;
import com.java.system.agent.runtime.domain.handle.HandleBinding;

import java.util.HashSet;
import java.util.List;
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
        if (context.finalResponseMode()) {
            if (action instanceof AnswerAction answer) {
                return validateAnswer(answer, context, true);
            }
            if (action instanceof ClarifyAction clarify) {
                return finalResponseValidation(action, clarify.candidates(), context);
            }
            return rejected(ActionRejectionCode.FINAL_RESPONSE_REQUIRED, action);
        }
        if (action instanceof QueryAction query) return validateQuery(query, context);
        if (action instanceof AnswerAction answer) return validateAnswer(answer, context, false);
        if (action instanceof ClarifyAction clarify) {
            return validateNonQuery(clarify, clarify.candidates(), context);
        }
        return rejected(ActionRejectionCode.UNKNOWN_ACTION, action);
    }

    private ActionValidation validateAnswer(
            AnswerAction action,
            AgentValidationContext context,
            boolean finalResponseMode) {
        try {
            answerDocumentValidator.validate(
                    action.document(), context.evidence(), context.observations(), context.currentBinding());
        } catch (AnswerDocumentContractException exception) {
            return rejected(exception.rejectionCode(), action);
        }
        return finalResponseMode
                ? finalResponseValidation(action, List.of(), context)
                : validateNonQuery(action, List.of(), context);
    }

    private ActionValidation validateQuery(QueryAction action, AgentValidationContext context) {
        if (!hasCurrentAttemptBinding(action.capability(), action.candidates(), context)) {
            return rejected(ActionRejectionCode.CROSS_ATTEMPT, action);
        }
        if (!allCandidatesIssued(action.candidates(), context)) return rejected(ActionRejectionCode.UNKNOWN_CANDIDATE, action);
        if (!hasCurrentRevision(action.capability(), context.currentBinding())) {
            return rejected(ActionRejectionCode.STALE_REVISION, action);
        }
        if (!context.capabilities().containsKey(action.capability())) return rejected(ActionRejectionCode.UNKNOWN_CAPABILITY, action);
        if (!supportsCandidateKinds(action.candidates(), context.capabilities().get(action.capability()), context)) {
            return rejected(ActionRejectionCode.INCOMPATIBLE_CANDIDATE_KIND, action);
        }
        if (!hasPermittedCardinality(action.candidates(), context.capabilities().get(action.capability()))) {
            return rejected(ActionRejectionCode.CARDINALITY, action);
        }
        if (hasDuplicateCandidates(action.candidates())) return rejected(ActionRejectionCode.DUPLICATE_CANDIDATE, action);
        if (!allCandidatesMatchRevision(action.candidates(), context)) return rejected(ActionRejectionCode.STALE_REVISION, action);
        try {
            context.capabilities().get(action.capability()).querySchema().validate(action.arguments());
        } catch (CapabilityQueryContractException exception) {
            return rejected(ActionRejectionCode.INVALID_ARGUMENTS, action);
        }
        return context.budget().hasAgentStepRemaining() && context.budget().hasSemanticQueryRemaining()
                ? new ActionValidation.Accepted(action)
                : rejected(ActionRejectionCode.BUDGET_EXHAUSTED, action);
    }

    private ActionValidation validateNonQuery(
            AgentAction action,
            List<CandidateHandle> candidateHandles,
            AgentValidationContext context) {
        Optional<ActionRejectionCode> candidateRejection = candidateReferenceRejection(candidateHandles, context);
        if (candidateRejection.isPresent()) return rejected(candidateRejection.orElseThrow(), action);
        return context.budget().hasAgentStepRemaining()
                ? new ActionValidation.Accepted(action)
                : rejected(ActionRejectionCode.BUDGET_EXHAUSTED, action);
    }

    private ActionValidation finalResponseValidation(
            AgentAction action,
            List<CandidateHandle> candidateHandles,
            AgentValidationContext context) {
        Optional<ActionRejectionCode> candidateRejection = candidateReferenceRejection(candidateHandles, context);
        if (candidateRejection.isPresent()) return rejected(candidateRejection.orElseThrow(), action);
        return context.budget().hasFinalAnswerRemaining()
                ? new ActionValidation.Accepted(action)
                : rejected(ActionRejectionCode.BUDGET_EXHAUSTED, action);
    }

    private static boolean hasCurrentAttemptBinding(
            CapabilityHandle capability,
            List<CandidateHandle> candidates,
            AgentValidationContext context) {
        if (Objects.nonNull(capability) && !sameAttempt(capability.binding(), context.currentBinding())) return false;
        return candidates.stream().allMatch(candidate -> sameAttempt(candidate.binding(), context.currentBinding()));
    }

    private static boolean hasCurrentRevision(CapabilityHandle capability, HandleBinding currentBinding) {
        return capability.binding().revisionVector().equals(currentBinding.revisionVector());
    }

    private static boolean allCandidatesIssued(List<CandidateHandle> candidates, AgentValidationContext context) {
        return candidates.stream().allMatch(context.candidates()::containsKey);
    }

    private static Optional<ActionRejectionCode> candidateReferenceRejection(
            List<CandidateHandle> candidates,
            AgentValidationContext context) {
        if (!hasCurrentAttemptBinding(null, candidates, context)) return Optional.of(ActionRejectionCode.CROSS_ATTEMPT);
        if (!allCandidatesIssued(candidates, context)) return Optional.of(ActionRejectionCode.UNKNOWN_CANDIDATE);
        if (hasDuplicateCandidates(candidates)) return Optional.of(ActionRejectionCode.DUPLICATE_CANDIDATE);
        if (!allCandidatesMatchRevision(candidates, context)) return Optional.of(ActionRejectionCode.STALE_REVISION);
        return Optional.empty();
    }

    private static boolean supportsCandidateKinds(
            List<CandidateHandle> candidates,
            CapabilityDescriptor capability,
            AgentValidationContext context) {
        return candidates.stream().allMatch(candidate -> capability.acceptedCandidateKinds()
                .contains(context.candidates().get(candidate).candidate().kind()));
    }

    private static boolean hasPermittedCardinality(
            List<CandidateHandle> candidates,
            CapabilityDescriptor capability) {
        int size = candidates.size();
        return size >= capability.minimumCandidates() && size <= capability.maximumCandidates();
    }

    private static boolean hasDuplicateCandidates(List<CandidateHandle> candidates) {
        Set<CandidateHandle> seen = new HashSet<>();
        return candidates.stream().anyMatch(candidate -> !seen.add(candidate));
    }

    private static boolean allCandidatesMatchRevision(List<CandidateHandle> candidates, AgentValidationContext context) {
        return candidates.stream().allMatch(candidate -> candidateMatchesRevision(context.candidates().get(candidate), context));
    }

    private static boolean candidateMatchesRevision(IssuedCandidate candidate, AgentValidationContext context) {
        if (!candidate.handle().binding().revisionVector().equals(context.currentBinding().revisionVector())) return false;
        return candidate.candidate().repositoryRevision().isEmpty() || context.currentBinding().revisionVector()
                .matches(candidate.candidate().repositoryId(), candidate.candidate().repositoryRevision().orElseThrow());
    }

    private static boolean sameAttempt(HandleBinding left, HandleBinding right) {
        return left.runId().equals(right.runId()) && left.attemptId().equals(right.attemptId());
    }

    private static ActionValidation.Rejected rejected(ActionRejectionCode code, AgentAction action) { return new ActionValidation.Rejected(code, code.name(), action); }
}
