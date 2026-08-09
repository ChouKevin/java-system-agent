package com.java.system.agent.capability.planning;

import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.FollowUpCandidate;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
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
 * 將本輪已發行的 follow-up handle 轉為使用 provider 綁定 payload 的普通 QUERY action
 */
public final class FollowUpPlanningToolRegistration implements PlanningToolRegistration<FollowUpPlanningInput> {

    private static final String NAME = "codebase_follow_up";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Execute one provider-bound target capability and payload by opaque FOLLOW_UP candidate handle";
    }

    @Override
    public Class<FollowUpPlanningInput> planningInputType() {
        return FollowUpPlanningInput.class;
    }

    @Override
    public boolean isIssued(AgentPromptContext context) {
        Objects.requireNonNull(context, "agent prompt context must not be null");
        return context.issuedCandidates().entrySet().stream()
                .anyMatch(candidate -> hasEligibleTarget(context, candidate));
    }

    @Override
    public AgentAction toAction(FollowUpPlanningInput input, AgentPromptContext context) {
        Objects.requireNonNull(input, "follow-up planning input must not be null");
        Objects.requireNonNull(context, "agent prompt context must not be null");
        Map.Entry<CandidateHandle, IssuedCandidate> selected = context.issuedCandidates().entrySet().stream()
                .filter(entry -> entry.getKey().value().equals(input.followUpCandidateHandle()))
                .findFirst()
                .orElseThrow(PlanningToolInputException::new);
        if (!hasCurrentBinding(selected.getKey().binding(), context)
                || !(selected.getValue().candidate() instanceof FollowUpCandidate followUp)) {
            throw new PlanningToolInputException();
        }
        CapabilityHandle target = targetCapability(context, selected.getKey(), followUp)
                .orElseThrow(PlanningToolInputException::new);
        CapabilityPolicy policy = context.issuedCapabilities().get(target);
        CapabilityInputPayload payload = boundPayload(context, target, policy,
                List.of(new CandidateHandleRef(selected.getKey().value()))).orElseThrow(PlanningToolInputException::new);
        return new QueryAction(target, List.of(new CandidateHandleRef(selected.getKey().value())),
                input.questionToResolve(), payload, input.rationale());
    }

    static Optional<CapabilityInputPayload> boundPayload(
            AgentPromptContext context,
            CapabilityHandle capability,
            CapabilityPolicy policy,
            List<CandidateHandleRef> candidateReferences) {
        Objects.requireNonNull(context, "agent prompt context must not be null");
        Objects.requireNonNull(capability, "capability handle must not be null");
        Objects.requireNonNull(policy, "capability policy must not be null");
        Objects.requireNonNull(candidateReferences, "candidate references must not be null");
        List<Map.Entry<CandidateHandle, IssuedCandidate>> selectedFollowUps = context.issuedCandidates().entrySet().stream()
                .filter(entry -> selectedBy(candidateReferences, entry.getKey()))
                .filter(entry -> entry.getValue().candidate() instanceof FollowUpCandidate)
                .toList();
        if (selectedFollowUps.isEmpty()) {
            return Optional.empty();
        }
        if (candidateReferences.size() != 1 || selectedFollowUps.size() != 1) {
            throw new PlanningToolInputException();
        }
        Map.Entry<CandidateHandle, IssuedCandidate> selected = selectedFollowUps.getFirst();
        FollowUpCandidate followUp = (FollowUpCandidate) selected.getValue().candidate();
        if (!hasMatchingScope(context, capability, selected.getKey(), followUp) || !matches(policy, followUp)) {
            throw new PlanningToolInputException();
        }
        return Optional.of(followUp.payload());
    }

    private static boolean hasEligibleTarget(
            AgentPromptContext context,
            Map.Entry<CandidateHandle, IssuedCandidate> candidate) {
        if (!hasCurrentBinding(candidate.getKey().binding(), context)
                || !(candidate.getValue().candidate() instanceof FollowUpCandidate followUp)) {
            return false;
        }
        return targetCapability(context, candidate.getKey(), followUp).isPresent();
    }

    private static Optional<CapabilityHandle> targetCapability(
            AgentPromptContext context,
            CandidateHandle candidate,
            FollowUpCandidate followUp) {
        return context.issuedCapabilities().entrySet().stream()
                .filter(entry -> hasMatchingScope(context, entry.getKey(), candidate, followUp))
                .filter(entry -> matches(entry.getValue(), followUp))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    private static boolean selectedBy(List<CandidateHandleRef> references, CandidateHandle handle) {
        return references.stream().anyMatch(reference -> reference.value().equals(handle.value()));
    }

    private static boolean hasMatchingScope(
            AgentPromptContext context,
            CapabilityHandle capability,
            CandidateHandle candidate,
            FollowUpCandidate followUp) {
        return hasCurrentBinding(capability.binding(), context)
                && hasCurrentBinding(candidate.binding(), context)
                && capability.binding().revisionVector().equals(candidate.binding().revisionVector())
                && candidate.binding().revisionVector().matches(followUp.repositoryId(), followUp.analyzedRevision());
    }

    private static boolean hasCurrentBinding(HandleBinding binding, AgentPromptContext context) {
        return binding.runId().equals(context.runId())
                && binding.attemptId().equals(context.attemptId());
    }

    private static boolean matches(CapabilityPolicy policy, FollowUpCandidate followUp) {
        return policy.name().equals(followUp.targetCapabilityName())
                && policy.version().equals(followUp.targetCapabilityVersion());
    }
}
