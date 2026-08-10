package com.java.system.agent.capability.planning;

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

/** 驗證 provider follow-up authority 並提供其 canonical execution payload。 */
final class ProviderBoundFollowUp {

    private ProviderBoundFollowUp() {
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

    static Optional<Selection> selection(
            AgentPromptContext context,
            CapabilityPolicy policy,
            CandidateHandleRef reference) {
        Objects.requireNonNull(context, "agent prompt context must not be null");
        Objects.requireNonNull(policy, "follow-up target policy must not be null");
        Objects.requireNonNull(reference, "follow-up candidate reference must not be null");
        List<Map.Entry<CandidateHandle, IssuedCandidate>> candidates = context.issuedCandidates().entrySet().stream()
                .filter(entry -> entry.getKey().value().equals(reference.value()))
                .toList();
        if (candidates.size() != 1) {
            return Optional.empty();
        }
        Map.Entry<CandidateHandle, IssuedCandidate> candidate = candidates.getFirst();
        if (!(candidate.getValue().candidate() instanceof FollowUpCandidate followUp)) {
            return Optional.empty();
        }
        return targetCapability(context, candidate, policy)
                .map(capability -> new Selection(capability, reference, followUp.payload()));
    }

    static Optional<CapabilityHandle> targetCapability(
            AgentPromptContext context,
            Map.Entry<CandidateHandle, IssuedCandidate> candidate,
            CapabilityPolicy policy) {
        Objects.requireNonNull(context, "agent prompt context must not be null");
        Objects.requireNonNull(candidate, "issued follow-up candidate must not be null");
        Objects.requireNonNull(policy, "follow-up target policy must not be null");
        if (!hasCurrentBinding(candidate.getKey().binding(), context)
                || !(candidate.getValue().candidate() instanceof FollowUpCandidate followUp)) {
            return Optional.empty();
        }
        return context.issuedCapabilities().entrySet().stream()
                .filter(entry -> entry.getValue().equals(policy))
                .filter(entry -> hasMatchingScope(context, entry.getKey(), candidate.getKey(), followUp))
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

    /**
     * 完整驗證目前 follow-up authority 後保留的 capability、候選 reference 與 provider payload
     */
    record Selection(
            CapabilityHandle capability,
            CandidateHandleRef candidateReference,
            CapabilityInputPayload providerPayload) {

        Selection {
            Objects.requireNonNull(capability, "follow-up selection capability must not be null");
            Objects.requireNonNull(candidateReference, "follow-up selection candidate reference must not be null");
            Objects.requireNonNull(providerPayload, "follow-up selection payload must not be null");
        }
    }
}
