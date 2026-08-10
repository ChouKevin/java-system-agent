package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.answering.domain.candidate.AnalysisCandidate;
import com.java.system.agent.answering.domain.candidate.SemanticTargetCandidate;
import com.java.system.agent.capability.planning.CandidateBoundExecutionPlanner;
import com.java.system.agent.capability.planning.PlanningToolInputException;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticCandidateTargetMapper;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 將 code intelligence candidate 綁定為受保護 execution input 的 planning 策略集合
 */
public final class CodeIntelligenceCandidateExecutionPlanners {

    private static final String INVALID_METHOD_TARGET = "reason=CANDIDATE_SELECTION; "
            + "invalidFields=[candidateHandles]; "
            + "constraints=[candidateHandles:ExactMethodTarget]";
    private static final String INVALID_TYPE_MEMBER_FILTER = "reason=CANDIDATE_INPUT; "
            + "invalidFields=[initialFilter]; "
            + "constraints=[initialFilter:ContinuationFilter]";
    private static final String MISSING_TYPE_MEMBER_FILTER = "reason=CANDIDATE_INPUT; "
            + "invalidFields=[initialFilter]; "
            + "constraints=[initialFilter:RequiredForDirectCandidate]";
    private static final String INVALID_SOURCE_RANGE = "reason=CANDIDATE_SELECTION; "
            + "invalidFields=[candidateHandles]; "
            + "constraints=[candidateHandles:ExactSourceRange]";

    private CodeIntelligenceCandidateExecutionPlanners() {
    }

    public static CandidateBoundExecutionPlanner<DiscoverMethodImplementationsPlanningInput,
            DiscoverMethodImplementationsExecutionInput> discoverMethodImplementations() {
        return new DiscoverMethodImplementationsPlanner(new JavaSemanticCandidateTargetMapper());
    }

    public static CandidateBoundExecutionPlanner<DiscoverTypeMembersPlanningInput,
            DiscoverTypeMembersExecutionInput> discoverTypeMembers() {
        return new DiscoverTypeMembersPlanner(new JavaSemanticCandidateTargetMapper());
    }

    public static CandidateBoundExecutionPlanner<GetSourceSegmentPlanningInput,
            GetSourceSegmentExecutionInput> getSourceSegment() {
        return new GetSourceSegmentPlanner(new JavaSemanticCandidateTargetMapper());
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

    private static final class DiscoverTypeMembersPlanner implements CandidateBoundExecutionPlanner<
            DiscoverTypeMembersPlanningInput, DiscoverTypeMembersExecutionInput> {

        private final JavaSemanticCandidateTargetMapper targetMapper;

        private DiscoverTypeMembersPlanner(JavaSemanticCandidateTargetMapper targetMapper) {
            this.targetMapper = Objects.requireNonNull(targetMapper, "semantic candidate target mapper must not be null");
        }

        @Override
        public boolean supportsDirectCandidate(AnalysisCandidate candidate) {
            return candidate instanceof SemanticTargetCandidate semanticTarget
                    && targetMapper.supportsMethodTarget(semanticTarget.semanticTarget());
        }

        @Override
        public DiscoverTypeMembersExecutionInput planDirect(DiscoverTypeMembersPlanningInput input,
                                                             AnalysisCandidate candidate) {
            if (!(candidate instanceof SemanticTargetCandidate semanticTarget)
                    || !targetMapper.supportsMethodTarget(semanticTarget.semanticTarget())) {
                throw invalidMethodTarget();
            }
            DiscoverTypeMembersPlanningInput.InitialFilter filter = input.initialFilter()
                    .orElseThrow(CodeIntelligenceCandidateExecutionPlanners::missingTypeMemberFilter);
            return new DiscoverTypeMembersExecutionInput(targetMapper.sourceType(semanticTarget.semanticTarget()),
                    memberKinds(filter), filter.namePrefix(), 0, Optional.ofNullable(input.limit()).orElse(50));
        }

        @Override
        public DiscoverTypeMembersExecutionInput planFollowUp(DiscoverTypeMembersPlanningInput input,
                                                               DiscoverTypeMembersExecutionInput providerInput) {
            if (providerInput.offset() > 0 && input.initialFilter().isPresent()
                    && !matches(providerInput, input.initialFilter().orElseThrow())) {
                throw invalidTypeMemberFilter();
            }
            Optional<DiscoverTypeMembersPlanningInput.InitialFilter> replacement = providerInput.offset() == 0
                    ? input.initialFilter()
                    : Optional.empty();
            List<String> memberKinds = replacement.map(DiscoverTypeMembersPlanner::memberKinds)
                    .orElse(providerInput.memberKinds());
            Optional<String> namePrefix = replacement.map(DiscoverTypeMembersPlanningInput.InitialFilter::namePrefix)
                    .orElse(providerInput.namePrefix());
            int limit = Optional.ofNullable(input.limit()).orElse(providerInput.limit());
            return new DiscoverTypeMembersExecutionInput(providerInput.sourceType(), memberKinds, namePrefix,
                    providerInput.offset(), limit);
        }

        private static boolean matches(DiscoverTypeMembersExecutionInput providerInput,
                                       DiscoverTypeMembersPlanningInput.InitialFilter filter) {
            return providerInput.memberKinds().equals(memberKinds(filter))
                    && providerInput.namePrefix().equals(filter.namePrefix());
        }

        private static List<String> memberKinds(DiscoverTypeMembersPlanningInput.InitialFilter filter) {
            return filter.memberKinds().stream().map(Enum::name).toList();
        }
    }

    private static final class GetSourceSegmentPlanner implements CandidateBoundExecutionPlanner<
            GetSourceSegmentPlanningInput, GetSourceSegmentExecutionInput> {

        private final JavaSemanticCandidateTargetMapper targetMapper;

        private GetSourceSegmentPlanner(JavaSemanticCandidateTargetMapper targetMapper) {
            this.targetMapper = Objects.requireNonNull(targetMapper, "semantic candidate target mapper must not be null");
        }

        @Override
        public boolean supportsDirectCandidate(AnalysisCandidate candidate) {
            return candidate instanceof SemanticTargetCandidate semanticTarget
                    && targetMapper.supportsSourceRange(semanticTarget.semanticTarget());
        }

        @Override
        public GetSourceSegmentExecutionInput planDirect(GetSourceSegmentPlanningInput input, AnalysisCandidate candidate) {
            if (!(candidate instanceof SemanticTargetCandidate semanticTarget)
                    || !targetMapper.supportsSourceRange(semanticTarget.semanticTarget())) {
                throw invalidSourceRange();
            }
            return new GetSourceSegmentExecutionInput(targetMapper.sourceRange(semanticTarget.semanticTarget()),
                    Optional.ofNullable(input.contextLines()).orElse(0));
        }

        @Override
        public GetSourceSegmentExecutionInput planFollowUp(GetSourceSegmentPlanningInput input,
                                                            GetSourceSegmentExecutionInput providerInput) {
            return new GetSourceSegmentExecutionInput(providerInput.location(),
                    Optional.ofNullable(input.contextLines()).orElse(providerInput.contextLines()));
        }
    }

    private static PlanningToolInputException invalidMethodTarget() {
        return PlanningToolInputException.safeDiagnostic(INVALID_METHOD_TARGET);
    }

    private static PlanningToolInputException missingTypeMemberFilter() {
        return PlanningToolInputException.safeDiagnostic(MISSING_TYPE_MEMBER_FILTER);
    }

    private static PlanningToolInputException invalidTypeMemberFilter() {
        return PlanningToolInputException.safeDiagnostic(INVALID_TYPE_MEMBER_FILTER);
    }

    private static PlanningToolInputException invalidSourceRange() {
        return PlanningToolInputException.safeDiagnostic(INVALID_SOURCE_RANGE);
    }
}
