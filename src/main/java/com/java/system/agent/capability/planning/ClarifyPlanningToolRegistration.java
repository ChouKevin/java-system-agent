package com.java.system.agent.capability.planning;

import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.action.ClarifyAction;
import com.java.system.agent.answering.domain.handle.CandidateHandleRef;
import com.java.system.agent.answering.port.out.AgentPromptContext;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 將固定 CLARIFY planning tool 的 typed input 與 mapper 綁為單一 registration
 */
public final class ClarifyPlanningToolRegistration<I> implements PlanningToolRegistration<I> {

    private static final String INVALID_CANDIDATE_SELECTION = "reason=CANDIDATE_SELECTION; "
            + "invalidFields=[candidateHandles]; "
            + "constraints=[candidateHandles:CurrentlyAuthorizedCandidate]";

    private final String name;
    private final Class<I> planningInputType;
    private final Function<I, ClarifyAction> mapper;
    private final PlanningToolDescriptor descriptor;

    public ClarifyPlanningToolRegistration(
            String name,
            Class<I> planningInputType,
            Function<I, ClarifyAction> mapper) {
        this.name = Objects.requireNonNull(name, "clarify planning tool name must not be null");
        this.planningInputType = Objects.requireNonNull(planningInputType, "clarify planning input type must not be null");
        this.mapper = Objects.requireNonNull(mapper, "clarify planning mapper must not be null");
        this.descriptor = PlanningToolDescriptor.core(PlanningToolCategory.CLARIFY, this.name);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public PlanningToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public Class<I> planningInputType() {
        return planningInputType;
    }

    @Override
    public List<CandidateHandleRef> allowedCandidateHandles(AgentPromptContext context) {
        Objects.requireNonNull(context, "agent prompt context must not be null");
        return context.issuedCandidates().keySet().stream()
                .map(handle -> new CandidateHandleRef(handle.value()))
                .toList();
    }

    @Override
    public AgentAction toAction(I input, AgentPromptContext context) {
        Objects.requireNonNull(input, "clarify planning input must not be null");
        Objects.requireNonNull(context, "agent prompt context must not be null");
        ClarifyAction action = mapper.apply(input);
        if (!allowedCandidateHandles(context).containsAll(action.candidates())) {
            throw PlanningToolInputException.safeDiagnostic(INVALID_CANDIDATE_SELECTION);
        }
        return action;
    }
}
