package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.answering.domain.candidate.AnalysisCandidate;
import com.java.system.agent.answering.domain.candidate.SemanticTargetCandidate;
import com.java.system.agent.capability.planning.CandidateBoundExecutionPlanner;
import com.java.system.agent.capability.planning.PlanningToolInputException;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticCandidateTargetMapper;

import java.util.Objects;
import java.util.Optional;

/**
 * 將 code intelligence candidate 綁定為受保護 execution input 的 planning 策略集合
 */
public final class CodeIntelligenceCandidateExecutionPlanners {

    private static final String INVALID_METHOD_TARGET = "reason=CANDIDATE_SELECTION; "
            + "invalidFields=[candidateHandles]; "
            + "constraints=[candidateHandles:ExactMethodTarget]";

    private CodeIntelligenceCandidateExecutionPlanners() {
    }

    public static CandidateBoundExecutionPlanner<DiscoverMethodImplementationsPlanningInput,
            DiscoverMethodImplementationsExecutionInput> discoverMethodImplementations() {
        return new DiscoverMethodImplementationsPlanner(new JavaSemanticCandidateTargetMapper());
    }

    private static final class DiscoverMethodImplementationsPlanner implements CandidateBoundExecutionPlanner<
            DiscoverMethodImplementationsPlanningInput, DiscoverMethodImplementationsExecutionInput> {

        private final JavaSemanticCandidateTargetMapper targetMapper;

        private DiscoverMethodImplementationsPlanner(JavaSemanticCandidateTargetMapper targetMapper) {
            this.targetMapper = Objects.requireNonNull(targetMapper, "semantic candidate target mapper must not be null");
        }

        @Override
        public boolean supportsDirectCandidate(AnalysisCandidate candidate) {
            return candidate instanceof SemanticTargetCandidate semanticTarget
                    && targetMapper.supportsMethodTarget(semanticTarget.semanticTarget());
        }

        @Override
        public DiscoverMethodImplementationsExecutionInput planDirect(
                DiscoverMethodImplementationsPlanningInput input,
                AnalysisCandidate candidate) {
            if (!(candidate instanceof SemanticTargetCandidate semanticTarget)
                    || !targetMapper.supportsMethodTarget(semanticTarget.semanticTarget())) {
                throw invalidMethodTarget();
            }
            return new DiscoverMethodImplementationsExecutionInput(Optional.of(targetMapper.methodTarget(
                    semanticTarget.semanticTarget())));
        }

        @Override
        public DiscoverMethodImplementationsExecutionInput planFollowUp(
                DiscoverMethodImplementationsPlanningInput input,
                DiscoverMethodImplementationsExecutionInput providerInput) {
            if (providerInput.boundTarget().isEmpty()) {
                throw invalidMethodTarget();
            }
            return providerInput;
        }
    }

    private static PlanningToolInputException invalidMethodTarget() {
        return PlanningToolInputException.safeDiagnostic(INVALID_METHOD_TARGET);
    }
}
