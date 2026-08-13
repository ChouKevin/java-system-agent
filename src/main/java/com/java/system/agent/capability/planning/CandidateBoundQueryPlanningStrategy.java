package com.java.system.agent.capability.planning;

import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.AnalysisCandidate;
import com.java.system.agent.answering.domain.candidate.FollowUpCandidate;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.candidate.RepositoryCandidate;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.handle.CandidateHandleRef;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.port.out.AgentPromptContext;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 將單一目前候選綁定為 QUERY execution input 的 planning strategy
 */
final class CandidateBoundQueryPlanningStrategy<P extends CandidateBoundPlanningInput, E>
        implements QueryPlanningStrategy<P, E> {

    private static final String INVALID_CANDIDATE_SELECTION = "reason=CANDIDATE_SELECTION; "
            + "invalidFields=[candidateHandles]; "
            + "constraints=[candidateHandles:CurrentlyAuthorizedCandidate]";
    private static final String CANDIDATE_INPUT_REASON = "reason=CANDIDATE_INPUT; invalidFields=[%s]; "
            + "constraints=[%s:NotBlank]";

    private final CapabilityPolicy policy;
    private final Class<E> executionInputType;
    private final CandidateBoundExecutionPlanner<P, E> planner;
    private final CanonicalCapabilityPayloadCodec payloadCodec;

    CandidateBoundQueryPlanningStrategy(
            CapabilityPolicy policy,
            Class<E> executionInputType,
            CandidateBoundExecutionPlanner<P, E> planner,
            CanonicalCapabilityPayloadCodec payloadCodec) {
        this.policy = Objects.requireNonNull(policy, "candidate-bound policy must not be null");
        this.executionInputType = Objects.requireNonNull(executionInputType,
                "candidate-bound execution input type must not be null");
        this.planner = Objects.requireNonNull(planner, "candidate-bound execution planner must not be null");
        this.payloadCodec = Objects.requireNonNull(payloadCodec, "candidate-bound payload codec must not be null");
    }

    @Override
    public boolean isIssued(AgentPromptContext context) {
        Objects.requireNonNull(context, "agent prompt context must not be null");
        return currentCapability(context).isPresent() && !allowedCandidateHandles(context).isEmpty();
    }

    @Override
    public List<CandidateHandleRef> allowedCandidateHandles(AgentPromptContext context) {
        Objects.requireNonNull(context, "agent prompt context must not be null");
        return context.issuedCandidates().entrySet().stream()
                .filter(candidate -> isCompatible(context, candidate))
                .map(candidate -> new CandidateHandleRef(candidate.getKey().value()))
                .toList();
    }

    @Override
    public AgentAction toAction(P input, AgentPromptContext context) {
        Objects.requireNonNull(input, "candidate-bound planning input must not be null");
        Objects.requireNonNull(context, "agent prompt context must not be null");
        validatePlanningText(input);
        CandidateHandleRef reference = selectedReference(input);
        if (!allowedCandidateHandles(context).contains(reference)) {
            throw invalidCandidateSelection();
        }
        Map.Entry<CandidateHandle, IssuedCandidate> selected = selectedCandidate(context, reference);
        CapabilityHandle capability = currentCapability(context).orElseThrow(
                CandidateBoundQueryPlanningStrategy::invalidCandidateSelection);
        AnalysisCandidate candidate = selected.getValue().candidate();
        E executionInput = candidate instanceof FollowUpCandidate
                ? planFollowUp(input, context, reference)
                : planDirect(input, context, capability, selected);
        return new QueryAction(capability, input.questionToResolve(), payloadCodec.encode(executionInput), input.rationale());
    }

    private boolean isCompatible(AgentPromptContext context, Map.Entry<CandidateHandle, IssuedCandidate> candidate) {
        CandidateHandleRef reference = new CandidateHandleRef(candidate.getKey().value());
        if (candidate.getValue().candidate() instanceof FollowUpCandidate) {
            return ProviderBoundFollowUp.selection(context, policy, reference).isPresent();
        }
        return supportsDirectCandidate(context, candidate);
    }

    private E planFollowUp(P input, AgentPromptContext context, CandidateHandleRef reference) {
        ProviderBoundFollowUp.Selection selection = ProviderBoundFollowUp.selection(context, policy, reference)
                .orElseThrow(CandidateBoundQueryPlanningStrategy::invalidCandidateSelection);
        E providerInput = payloadCodec.decode(selection.providerPayload(), executionInputType);
        return planner.planFollowUp(input, providerInput);
    }

    private E planDirect(
            P input,
            AgentPromptContext context,
            CapabilityHandle capability,
            Map.Entry<CandidateHandle, IssuedCandidate> selected) {
        if (!hasCurrentBinding(capability.binding(), context) || !supportsDirectCandidate(context, selected)) {
            throw invalidCandidateSelection();
        }
        return planner.planDirect(input, selected.getValue().candidate());
    }

    private boolean supportsDirectCandidate(
            AgentPromptContext context,
            Map.Entry<CandidateHandle, IssuedCandidate> candidate) {
        AnalysisCandidate analysisCandidate = candidate.getValue().candidate();
        return hasCurrentBinding(candidate.getKey().binding(), context)
                && policy.acceptedCandidateKinds().contains(analysisCandidate.kind())
                && isRevisionAuthorized(candidate.getKey().binding(), analysisCandidate)
                && planner.supportsDirectCandidate(analysisCandidate);
    }

    /**
     * Repository 初次探索交由 QUERY executor 解析 revision，其餘候選必須已與 binding 的 revision 一致
     */
    static boolean isRevisionAuthorized(HandleBinding binding, AnalysisCandidate candidate) {
        if (candidate instanceof RepositoryCandidate) {
            return true;
        }
        return binding.revisionVector().revisionOf(candidate.repositoryId())
                .map(pinnedRevision -> candidate.repositoryRevision().map(pinnedRevision::equals).orElse(false))
                .orElse(false);
    }

    private Optional<CapabilityHandle> currentCapability(AgentPromptContext context) {
        return context.issuedCapabilities().entrySet().stream()
                .filter(entry -> entry.getValue().equals(policy))
                .filter(entry -> hasCurrentBinding(entry.getKey().binding(), context))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    private static CandidateHandleRef selectedReference(CandidateBoundPlanningInput input) {
        List<String> candidateHandles = Objects.requireNonNull(input.candidateHandles(),
                "candidate-bound candidate handles must not be null");
        if (candidateHandles.size() != 1 || candidateHandles.stream().anyMatch(Objects::isNull)) {
            throw invalidCandidateSelection();
        }
        String handle = candidateHandles.getFirst();
        if (handle.isBlank()) {
            throw invalidCandidateSelection();
        }
        return new CandidateHandleRef(handle);
    }

    private static void validatePlanningText(CandidateBoundPlanningInput input) {
        if (isBlank(input.questionToResolve())) {
            throw invalidCandidateInput("questionToResolve");
        }
        if (isBlank(input.rationale())) {
            throw invalidCandidateInput("rationale");
        }
    }

    private static boolean isBlank(String value) {
        return Objects.isNull(value) || value.isBlank();
    }

    private static Map.Entry<CandidateHandle, IssuedCandidate> selectedCandidate(
            AgentPromptContext context,
            CandidateHandleRef reference) {
        List<Map.Entry<CandidateHandle, IssuedCandidate>> matches = context.issuedCandidates().entrySet().stream()
                .filter(entry -> entry.getKey().value().equals(reference.value()))
                .toList();
        if (matches.size() != 1) {
            throw invalidCandidateSelection();
        }
        return matches.getFirst();
    }

    private static boolean hasCurrentBinding(HandleBinding binding, AgentPromptContext context) {
        return binding.runId().equals(context.runId())
                && binding.attemptId().equals(context.attemptId());
    }

    static PlanningToolInputException invalidCandidateSelection() {
        return PlanningToolInputException.safeDiagnostic(INVALID_CANDIDATE_SELECTION);
    }

    private static PlanningToolInputException invalidCandidateInput(String invalidField) {
        return PlanningToolInputException.safeDiagnostic(CANDIDATE_INPUT_REASON.formatted(invalidField, invalidField));
    }
}

/**
 * Query registration 委派 issuance 與 action 投影的內部策略契約
 */
interface QueryPlanningStrategy<P, E> {

    boolean isIssued(AgentPromptContext context);

    List<CandidateHandleRef> allowedCandidateHandles(AgentPromptContext context);

    AgentAction toAction(P input, AgentPromptContext context);
}
