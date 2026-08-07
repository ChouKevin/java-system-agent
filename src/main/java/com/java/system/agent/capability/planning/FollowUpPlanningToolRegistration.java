package com.java.system.agent.capability.planning;

import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.FollowUpCandidate;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.handle.CandidateHandleRef;
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
        return "Plan an issued codebase follow-up using its bound payload";
    }

    @Override
    public Class<FollowUpPlanningInput> planningInputType() {
        return FollowUpPlanningInput.class;
    }

    @Override
    public boolean isIssued(AgentPromptContext context) {
        Objects.requireNonNull(context, "agent prompt context must not be null");
        return context.issuedCandidates().values().stream()
                .map(IssuedCandidate::candidate)
                .filter(FollowUpCandidate.class::isInstance)
                .map(FollowUpCandidate.class::cast)
                .anyMatch(followUp -> targetCapability(context, followUp).isPresent());
    }

    @Override
    public AgentAction toAction(FollowUpPlanningInput input, AgentPromptContext context) {
        Objects.requireNonNull(input, "follow-up planning input must not be null");
        Objects.requireNonNull(context, "agent prompt context must not be null");
        Map.Entry<CandidateHandle, IssuedCandidate> selected = context.issuedCandidates().entrySet().stream()
                .filter(entry -> entry.getKey().value().equals(input.followUpCandidateHandle()))
                .findFirst()
                .orElseThrow(PlanningToolInputException::new);
        if (!(selected.getValue().candidate() instanceof FollowUpCandidate followUp)) {
            throw new PlanningToolInputException();
        }
        CapabilityHandle target = targetCapability(context, followUp)
                .orElseThrow(PlanningToolInputException::new);
        return new QueryAction(target, List.of(new CandidateHandleRef(selected.getKey().value())),
                input.questionToResolve(), followUp.payload(), input.rationale());
    }

    private static Optional<CapabilityHandle> targetCapability(
            AgentPromptContext context,
            FollowUpCandidate followUp) {
        return context.issuedCapabilities().entrySet().stream()
                .filter(entry -> matches(entry.getValue(), followUp))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    private static boolean matches(CapabilityPolicy policy, FollowUpCandidate followUp) {
        return policy.name().equals(followUp.targetCapabilityName())
                && policy.version().equals(followUp.targetCapabilityVersion());
    }
}
