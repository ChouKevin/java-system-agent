package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.answering.domain.candidate.AnalysisCandidate;
import com.java.system.agent.answering.domain.candidate.RepositoryCandidate;
import com.java.system.agent.answering.domain.candidate.SemanticTargetCandidate;
import com.java.system.agent.capability.planning.CandidateBoundExecutionPlanner;
import com.java.system.agent.capability.planning.PlanningToolInputException;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticCandidateTargetMapper;
import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;

import java.util.HashSet;
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
    private static final String DUPLICATE_TYPE_MEMBER_KINDS = "reason=CANDIDATE_INPUT; "
            + "invalidFields=[initialFilter]; "
            + "constraints=[initialFilter:UniqueMemberKinds]";
    private static final String INVALID_SOURCE_RANGE = "reason=CANDIDATE_SELECTION; "
            + "invalidFields=[candidateHandles]; "
            + "constraints=[candidateHandles:ExactSourceRange]";
    private static final String MISSING_CONCEPT_CRITERIA = "reason=CANDIDATE_INPUT; "
            + "invalidFields=[searchCriteria]; "
            + "constraints=[searchCriteria:RequiredForDirectCandidate]";
    private static final String INVALID_CONCEPT_CRITERIA = "reason=CANDIDATE_INPUT; "
            + "invalidFields=[searchCriteria]; "
            + "constraints=[searchCriteria:ContinuationCriteria]";
    private static final String MISSING_EVENT_TYPE = "reason=CANDIDATE_INPUT; "
            + "invalidFields=[eventType]; "
            + "constraints=[eventType:RequiredForDirectCandidate]";
    private static final String INVALID_EVENT_TYPE = "reason=CANDIDATE_INPUT; "
            + "invalidFields=[eventType]; "
            + "constraints=[eventType:ContinuationEventType]";

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

    public static CandidateBoundExecutionPlanner<OutgoingCallGraphPlanningInput,
            OutgoingCallGraphExecutionInput> outgoingCallGraph() {
        return new OutgoingCallGraphPlanner(new JavaSemanticCandidateTargetMapper());
    }

    public static CandidateBoundExecutionPlanner<IncomingCallGraphPlanningInput,
            IncomingCallGraphExecutionInput> incomingCallGraph() {
        return new IncomingCallGraphPlanner(new JavaSemanticCandidateTargetMapper());
    }

    public static CandidateBoundExecutionPlanner<DiscoverConceptsPlanningInput,
            DiscoverConceptsExecutionInput> discoverConcepts() {
        return new DiscoverConceptsPlanner();
    }

    public static CandidateBoundExecutionPlanner<DiscoverEventListenersPlanningInput,
            DiscoverEventListenersExecutionInput> discoverEventListeners() {
        return new DiscoverEventListenersPlanner();
    }

    public static CandidateBoundExecutionPlanner<FindInternalReferencesPlanningInput,
            FindInternalReferencesExecutionInput> findInternalReferences() {
        return new FindInternalReferencesPlanner();
    }

    public static CandidateBoundExecutionPlanner<GetMethodSourcePlanningInput,
            GetMethodSourceExecutionInput> getMethodSource() {
        return new GetMethodSourcePlanner(new JavaSemanticCandidateTargetMapper());
    }

    public static CandidateBoundExecutionPlanner<ResolveSourceSymbolPlanningInput,
            ResolveSourceSymbolExecutionInput> resolveSourceSymbol() {
        return new ResolveSourceSymbolPlanner(new JavaSemanticCandidateTargetMapper());
    }

    private static final class OutgoingCallGraphPlanner implements CandidateBoundExecutionPlanner<
            OutgoingCallGraphPlanningInput, OutgoingCallGraphExecutionInput> {

        private final JavaSemanticCandidateTargetMapper targetMapper;

        private OutgoingCallGraphPlanner(JavaSemanticCandidateTargetMapper targetMapper) {
            this.targetMapper = Objects.requireNonNull(targetMapper, "semantic candidate target mapper must not be null");
        }

        @Override
        public boolean supportsDirectCandidate(AnalysisCandidate candidate) {
            return candidate instanceof SemanticTargetCandidate semanticTarget
                    && targetMapper.supportsMethodTarget(semanticTarget.semanticTarget());
        }

        @Override
        public OutgoingCallGraphExecutionInput planDirect(OutgoingCallGraphPlanningInput input, AnalysisCandidate candidate) {
            return new OutgoingCallGraphExecutionInput(depth(input.depth()), methodTarget(candidate, targetMapper));
        }

        @Override
        public OutgoingCallGraphExecutionInput planFollowUp(
                OutgoingCallGraphPlanningInput input, OutgoingCallGraphExecutionInput providerInput) {
            return new OutgoingCallGraphExecutionInput(followUpDepth(input.depth(), providerInput.depth()),
                    providerInput.target());
        }
    }

    private static final class IncomingCallGraphPlanner implements CandidateBoundExecutionPlanner<
            IncomingCallGraphPlanningInput, IncomingCallGraphExecutionInput> {

        private final JavaSemanticCandidateTargetMapper targetMapper;

        private IncomingCallGraphPlanner(JavaSemanticCandidateTargetMapper targetMapper) {
            this.targetMapper = Objects.requireNonNull(targetMapper, "semantic candidate target mapper must not be null");
        }

        @Override
        public boolean supportsDirectCandidate(AnalysisCandidate candidate) {
            return candidate instanceof SemanticTargetCandidate semanticTarget
                    && targetMapper.supportsMethodTarget(semanticTarget.semanticTarget());
        }

        @Override
        public IncomingCallGraphExecutionInput planDirect(IncomingCallGraphPlanningInput input, AnalysisCandidate candidate) {
            return new IncomingCallGraphExecutionInput(depth(input.depth()), methodTarget(candidate, targetMapper));
        }

        @Override
        public IncomingCallGraphExecutionInput planFollowUp(
                IncomingCallGraphPlanningInput input, IncomingCallGraphExecutionInput providerInput) {
            return new IncomingCallGraphExecutionInput(followUpDepth(input.depth(), providerInput.depth()),
                    providerInput.target());
        }
    }

    private static final class DiscoverConceptsPlanner implements CandidateBoundExecutionPlanner<
            DiscoverConceptsPlanningInput, DiscoverConceptsExecutionInput> {

        @Override
        public boolean supportsDirectCandidate(AnalysisCandidate candidate) {
            return candidate instanceof RepositoryCandidate;
        }

        @Override
        public DiscoverConceptsExecutionInput planDirect(DiscoverConceptsPlanningInput input, AnalysisCandidate candidate) {
            if (!(candidate instanceof RepositoryCandidate)) {
                throw invalidMethodTarget();
            }
            DiscoverConceptsPlanningInput.SearchCriteria criteria = input.searchCriteria()
                    .orElseThrow(CodeIntelligenceCandidateExecutionPlanners::missingConceptCriteria);
            return conceptsInput(criteria, 0, limit(input.limit(), 50));
        }

        @Override
        public DiscoverConceptsExecutionInput planFollowUp(
                DiscoverConceptsPlanningInput input, DiscoverConceptsExecutionInput providerInput) {
            input.searchCriteria().ifPresent(criteria -> {
                if (!matches(providerInput, criteria)) {
                    throw invalidConceptCriteria();
                }
            });
            return new DiscoverConceptsExecutionInput(providerInput.terms(), providerInput.kinds(), providerInput.packagePrefix(),
                    providerInput.offset(), limit(input.limit(), providerInput.limit()));
        }

        private static boolean matches(DiscoverConceptsExecutionInput providerInput,
                                       DiscoverConceptsPlanningInput.SearchCriteria criteria) {
            return providerInput.terms().equals(terms(criteria))
                    && providerInput.kinds().equals(kinds(criteria))
                    && providerInput.packagePrefix().equals(criteria.packagePrefix());
        }

        private static DiscoverConceptsExecutionInput conceptsInput(
                DiscoverConceptsPlanningInput.SearchCriteria criteria, int offset, int limit) {
            return new DiscoverConceptsExecutionInput(terms(criteria), kinds(criteria), criteria.packagePrefix(), offset, limit);
        }

        private static List<DiscoverConceptsExecutionInput.Term> terms(DiscoverConceptsPlanningInput.SearchCriteria criteria) {
            return criteria.terms().stream().map(term -> new DiscoverConceptsExecutionInput.Term(
                    term.value(), term.matchMode().name())).toList();
        }

        private static List<String> kinds(DiscoverConceptsPlanningInput.SearchCriteria criteria) {
            return criteria.kinds().stream().map(Enum::name).toList();
        }
    }

    private static final class DiscoverEventListenersPlanner implements CandidateBoundExecutionPlanner<
            DiscoverEventListenersPlanningInput, DiscoverEventListenersExecutionInput> {

        @Override
        public boolean supportsDirectCandidate(AnalysisCandidate candidate) {
            return candidate instanceof RepositoryCandidate;
        }

        @Override
        public DiscoverEventListenersExecutionInput planDirect(
                DiscoverEventListenersPlanningInput input, AnalysisCandidate candidate) {
            if (!(candidate instanceof RepositoryCandidate)) {
                throw invalidMethodTarget();
            }
            String eventType = input.eventType().orElseThrow(CodeIntelligenceCandidateExecutionPlanners::missingEventType);
            return new DiscoverEventListenersExecutionInput(eventType, 0, limit(input.limit(), 50));
        }

        @Override
        public DiscoverEventListenersExecutionInput planFollowUp(
                DiscoverEventListenersPlanningInput input, DiscoverEventListenersExecutionInput providerInput) {
            input.eventType().ifPresent(eventType -> {
                if (!eventType.equals(providerInput.eventType())) {
                    throw invalidEventType();
                }
            });
            return new DiscoverEventListenersExecutionInput(providerInput.eventType(), providerInput.offset(),
                    limit(input.limit(), providerInput.limit()));
        }
    }

    private static final class FindInternalReferencesPlanner implements CandidateBoundExecutionPlanner<
            FindInternalReferencesPlanningInput, FindInternalReferencesExecutionInput> {

        @Override
        public boolean supportsDirectCandidate(AnalysisCandidate candidate) {
            return false;
        }

        @Override
        public FindInternalReferencesExecutionInput planDirect(
                FindInternalReferencesPlanningInput input, AnalysisCandidate candidate) {
            throw invalidMethodTarget();
        }

        @Override
        public FindInternalReferencesExecutionInput planFollowUp(
                FindInternalReferencesPlanningInput input, FindInternalReferencesExecutionInput providerInput) {
            return new FindInternalReferencesExecutionInput(providerInput.target(), providerInput.offset(),
                    limit(input.limit(), providerInput.limit()));
        }
    }

    private static final class GetMethodSourcePlanner implements CandidateBoundExecutionPlanner<
            GetMethodSourcePlanningInput, GetMethodSourceExecutionInput> {

        private final JavaSemanticCandidateTargetMapper targetMapper;

        private GetMethodSourcePlanner(JavaSemanticCandidateTargetMapper targetMapper) {
            this.targetMapper = Objects.requireNonNull(targetMapper, "semantic candidate target mapper must not be null");
        }

        @Override
        public boolean supportsDirectCandidate(AnalysisCandidate candidate) {
            return candidate instanceof SemanticTargetCandidate semanticTarget
                    && targetMapper.supportsMethodTarget(semanticTarget.semanticTarget());
        }

        @Override
        public GetMethodSourceExecutionInput planDirect(GetMethodSourcePlanningInput input, AnalysisCandidate candidate) {
            return new GetMethodSourceExecutionInput(methodTarget(candidate, targetMapper));
        }

        @Override
        public GetMethodSourceExecutionInput planFollowUp(
                GetMethodSourcePlanningInput input, GetMethodSourceExecutionInput providerInput) {
            return providerInput;
        }
    }

    private static final class ResolveSourceSymbolPlanner implements CandidateBoundExecutionPlanner<
            ResolveSourceSymbolPlanningInput, ResolveSourceSymbolExecutionInput> {

        private final JavaSemanticCandidateTargetMapper targetMapper;

        private ResolveSourceSymbolPlanner(JavaSemanticCandidateTargetMapper targetMapper) {
            this.targetMapper = Objects.requireNonNull(targetMapper, "semantic candidate target mapper must not be null");
        }

        @Override
        public boolean supportsDirectCandidate(AnalysisCandidate candidate) {
            return candidate instanceof SemanticTargetCandidate semanticTarget
                    && targetMapper.supportsMethodTarget(semanticTarget.semanticTarget());
        }

        @Override
        public ResolveSourceSymbolExecutionInput planDirect(
                ResolveSourceSymbolPlanningInput input, AnalysisCandidate candidate) {
            if (!(candidate instanceof SemanticTargetCandidate semanticTarget)) {
                throw invalidMethodTarget();
            }
            Optional<SemanticDtos.Position> position = Optional.ofNullable(input.position()).orElse(Optional.empty());
            return new ResolveSourceSymbolExecutionInput(
                    input.symbol(), position, targetMapper.sourceSymbolContext(semanticTarget.semanticTarget()));
        }

        @Override
        public ResolveSourceSymbolExecutionInput planFollowUp(
                ResolveSourceSymbolPlanningInput input, ResolveSourceSymbolExecutionInput providerInput) {
            return providerInput;
        }
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
            return new DiscoverMethodImplementationsExecutionInput(targetMapper.methodTarget(semanticTarget.semanticTarget()));
        }

        @Override
        public DiscoverMethodImplementationsExecutionInput planFollowUp(
                DiscoverMethodImplementationsPlanningInput input,
                DiscoverMethodImplementationsExecutionInput providerInput) {
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
            validateUniqueMemberKinds(filter);
            return new DiscoverTypeMembersExecutionInput(targetMapper.sourceType(semanticTarget.semanticTarget()),
                    memberKinds(filter), filter.namePrefix(), 0, Optional.ofNullable(input.limit()).orElse(50));
        }

        @Override
        public DiscoverTypeMembersExecutionInput planFollowUp(DiscoverTypeMembersPlanningInput input,
                                                               DiscoverTypeMembersExecutionInput providerInput) {
            input.initialFilter().ifPresent(DiscoverTypeMembersPlanner::validateUniqueMemberKinds);
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

        private static void validateUniqueMemberKinds(DiscoverTypeMembersPlanningInput.InitialFilter filter) {
            if (filter.memberKinds().size() != new HashSet<>(filter.memberKinds()).size()) {
                throw duplicateTypeMemberKinds();
            }
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

    private static SemanticDtos.MethodTargetPayload methodTarget(
            AnalysisCandidate candidate, JavaSemanticCandidateTargetMapper targetMapper) {
        if (!(candidate instanceof SemanticTargetCandidate semanticTarget)
                || !targetMapper.supportsMethodTarget(semanticTarget.semanticTarget())) {
            throw invalidMethodTarget();
        }
        return targetMapper.methodTarget(semanticTarget.semanticTarget());
    }

    private static int depth(Integer configuredDepth) {
        return Optional.ofNullable(configuredDepth).orElse(2);
    }

    private static int followUpDepth(Integer configuredDepth, int providerDepth) {
        return Optional.ofNullable(configuredDepth).orElse(providerDepth);
    }

    private static int limit(Integer configuredLimit, int providerDefault) {
        return Optional.ofNullable(configuredLimit).orElse(providerDefault);
    }

    private static PlanningToolInputException missingConceptCriteria() {
        return PlanningToolInputException.safeDiagnostic(MISSING_CONCEPT_CRITERIA);
    }

    private static PlanningToolInputException invalidConceptCriteria() {
        return PlanningToolInputException.safeDiagnostic(INVALID_CONCEPT_CRITERIA);
    }

    private static PlanningToolInputException missingEventType() {
        return PlanningToolInputException.safeDiagnostic(MISSING_EVENT_TYPE);
    }

    private static PlanningToolInputException invalidEventType() {
        return PlanningToolInputException.safeDiagnostic(INVALID_EVENT_TYPE);
    }

    private static PlanningToolInputException missingTypeMemberFilter() {
        return PlanningToolInputException.safeDiagnostic(MISSING_TYPE_MEMBER_FILTER);
    }

    private static PlanningToolInputException invalidTypeMemberFilter() {
        return PlanningToolInputException.safeDiagnostic(INVALID_TYPE_MEMBER_FILTER);
    }

    private static PlanningToolInputException duplicateTypeMemberKinds() {
        return PlanningToolInputException.safeDiagnostic(DUPLICATE_TYPE_MEMBER_KINDS);
    }

    private static PlanningToolInputException invalidSourceRange() {
        return PlanningToolInputException.safeDiagnostic(INVALID_SOURCE_RANGE);
    }
}
