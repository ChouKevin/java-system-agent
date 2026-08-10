package com.java.system.agent.codeintelligence.semantic;

import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import jakarta.validation.Validation;
import com.java.system.agent.answering.domain.candidate.AnalysisCandidate;
import com.java.system.agent.answering.domain.candidate.FollowUpCandidate;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.candidate.RouteCandidate;
import com.java.system.agent.answering.domain.candidate.SemanticTargetCandidate;
import com.java.system.agent.answering.domain.evidence.EvidenceRef;
import com.java.system.agent.answering.domain.evidence.EvidenceWarning;
import com.java.system.agent.answering.domain.evidence.SemanticTarget;
import com.java.system.agent.answering.domain.observation.CapabilityObservation;
import com.java.system.agent.answering.domain.observation.ObservationCode;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.port.out.CapabilityExecutionContractException;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.answering.port.out.CapabilityInvocation;
import com.java.system.agent.answering.port.out.RepositoryDescriptor;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 將 Java Semantic Service v1 DTO 依原始順序轉成 answering 未配發的結果
 */
public final class JavaSemanticResultMapper {

    private static final String SOURCE_SERVICE = "java-semantic-service";
    private static final int MAX_TEXT_LENGTH = 1_000;

    private final JavaSemanticProviderSchemaValidator schemaValidator = new JavaSemanticProviderSchemaValidator();
    private final JavaSemanticMetadataEvidenceMapper metadataEvidenceMapper = new JavaSemanticMetadataEvidenceMapper();
    private final JavaSemanticCandidateTargetMapper targetMapper = new JavaSemanticCandidateTargetMapper();
    private final JavaSemanticFollowUpMapper followUpMapper;

    public JavaSemanticResultMapper() {
        this(new JavaSemanticFollowUpMapper(new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator())));
    }

    public JavaSemanticResultMapper(JavaSemanticFollowUpMapper followUpMapper) {
        this.followUpMapper = Objects.requireNonNull(followUpMapper, "Semantic follow-up mapper must not be null");
    }

    public List<RepositoryDescriptor> repositories(List<SemanticDtos.RepositoryStatusResponse> responses) {
        List<SemanticDtos.RepositoryStatusResponse> required = schemaValidator.repositories(responses);
        List<RepositoryDescriptor> descriptors = new ArrayList<>();
        for (SemanticDtos.RepositoryStatusResponse response : required) {
            descriptors.add(new RepositoryDescriptor(new RepositoryId(response.repoId()),
                    description(response.displayName(), "repository")));
        }
        return List.copyOf(descriptors);
    }

    public RepositoryRevision repositoryRevision(SemanticDtos.RepositoryStatusResponse response) {
        SemanticDtos.RepositoryStatusResponse required = repositoryStatus(response);
        if (Objects.isNull(required.currentRevision())) {
            throw contract("repository current revision is unavailable");
        }
        return new RepositoryRevision(required.currentRevision());
    }

    SemanticDtos.RepositoryStatusResponse repositoryStatus(SemanticDtos.RepositoryStatusResponse response) {
        return schemaValidator.repository(response);
    }

    public CapabilityExecutionResult listEntryPoints(CapabilityInvocation invocation,
                                                      SemanticDtos.EntryPointsResponse response) {
        requireProviderObject(invocation, "capability invocation");
        SemanticDtos.EntryPointsResponse requiredResponse = schemaValidator.entryPoints(response);
        RepositoryId repositoryId = new RepositoryId(requiredResponse.repoId());
        RepositoryRevision revision = new RepositoryRevision(requiredResponse.analyzedRevision());
        List<AnalysisCandidate> candidates = new ArrayList<>();
        List<EvidenceRef> evidence = new ArrayList<>();
        List<CapabilityObservation> observations = new ArrayList<>();
        for (SemanticDtos.EntryPointClassResponse entryPoint : requiredList(requiredResponse.entryPoints(), "entry point")) {
            String classDescription = description(entryPoint.description(), "entry point");
            for (SemanticDtos.EntryPointMethodResponse method : requiredList(entryPoint.methods(), "entry point method")) {
                String description = description(classDescription + " " + method.description(), "entry point method");
                if (method instanceof SemanticDtos.ApiEntryPointMethodResponse apiMethod) {
                    candidates.add(new RouteCandidate(repositoryId, revision,
                            apiMethod.apiUrl(), description));
                }
                addResolutionCandidates(repositoryId, revision, method.analysisTarget(), description, candidates,
                        observations);
                addFollowUps(repositoryId, revision, method.analysisTarget().availableFollowUps(), candidates);
                if ("RESOLVED".equals(method.analysisTarget().status())) {
                    SemanticTarget target = semanticTarget(method.analysisTarget().target());
                    evidence.add(metadataEvidenceMapper.entryPoint(repositoryId, revision, entryPoint, method, target));
                }
            }
        }
        return succeeded(candidates, evidence, observations);
    }

    public CapabilityExecutionResult apiRoutes(SemanticDtos.ApiRouteCandidatesResponse response) {
        SemanticDtos.ApiRouteCandidatesResponse requiredResponse = schemaValidator.apiRoutes(response);
        List<AnalysisCandidate> candidates = new ArrayList<>();
        List<CapabilityObservation> observations = new ArrayList<>();
        for (SemanticDtos.ApiRouteCandidateResponse route : requiredList(requiredResponse.candidates(), "API route candidate")) {
            RepositoryId repositoryId = new RepositoryId(route.repoId());
            RepositoryRevision revision = new RepositoryRevision(route.analyzedRevision());
            String description = description(route.httpMethod() + " " + route.routeTemplate() + " " + route.className()
                    + "#" + route.methodName(), "API route");
            candidates.add(new RouteCandidate(repositoryId, revision,
                    route.routeTemplate(), description));
            addResolutionCandidates(repositoryId, revision, route.analysisTarget(), description, candidates, observations);
        }
        for (SemanticDtos.ApiRouteObservationResponse observation : requiredList(requiredResponse.observations(),
                "API route observation")) {
            if (!"TRUNCATED_CANDIDATES".equals(requiredText(observation.code(), "API route observation code"))) {
                throw contract("unsupported API route observation code");
            }
            observations.add(observation(ObservationCode.TRUNCATED_CANDIDATES, observation.description(), List.of()));
        }
        return succeeded(candidates, List.of(), observations);
    }

    public CapabilityExecutionResult outgoingCallGraph(CapabilityInvocation invocation,
                                                        SemanticDtos.OutgoingCallGraphResponse response) {
        SemanticTargetCandidate target = selectedTarget(invocation);
        return outgoingCallGraph(target.repositoryId(), target.analyzedRevision(),
                methodTargetPayload(target.semanticTarget()), response);
    }

    CapabilityExecutionResult outgoingCallGraph(RepositoryId repositoryId, RepositoryRevision expectedRevision,
                                                SemanticDtos.MethodTargetPayload requestedTarget,
                                                SemanticDtos.OutgoingCallGraphResponse response) {
        SemanticDtos.OutgoingCallGraphResponse required = requireProviderObject(response, "outgoing call graph response");
        return callGraph(repositoryId, expectedRevision, requestedTarget, required.status(), required.analyzedRevision(),
                required.rootNodeId(),
                required.traversal(), required.nodes(), required.edges(), required.warnings(), required.errors());
    }

    public CapabilityExecutionResult incomingCallGraph(CapabilityInvocation invocation,
                                                        SemanticDtos.IncomingCallGraphResponse response) {
        SemanticTargetCandidate target = selectedTarget(invocation);
        return incomingCallGraph(target.repositoryId(), target.analyzedRevision(),
                methodTargetPayload(target.semanticTarget()), response);
    }

    CapabilityExecutionResult incomingCallGraph(RepositoryId repositoryId, RepositoryRevision expectedRevision,
                                                SemanticDtos.MethodTargetPayload requestedTarget,
                                                SemanticDtos.IncomingCallGraphResponse response) {
        SemanticDtos.IncomingCallGraphResponse required = requireProviderObject(response, "incoming call graph response");
        return callGraph(repositoryId, expectedRevision, requestedTarget, required.status(), required.analyzedRevision(),
                required.rootNodeId(),
                required.traversal(), required.nodes(), required.edges(), required.warnings(), required.errors());
    }

    public SemanticDtos.MethodTarget methodTarget(SemanticTarget target) {
        SemanticDtos.MethodTargetPayload methodTarget = targetMapper.methodTarget(target);
        return new SemanticDtos.MethodTarget(methodTarget.sourceType().sourceFile(),
                methodTarget.sourceType().javaType().packageName(), methodTarget.sourceType().javaType().className(),
                methodTarget.methodName(), methodTarget.parameterTypes());
    }

    public SemanticDtos.MethodTargetPayload methodTargetPayload(SemanticTarget target) {
        return targetMapper.methodTarget(target);
    }

    public SemanticTarget semanticTarget(SemanticDtos.MethodTarget target) {
        return targetMapper.semanticTarget(target);
    }

    /** 投影 provider 的結構化概念探索結果 */
    public CapabilityExecutionResult discoverConcepts(RepositoryId expectedRepositoryId, RepositoryRevision expectedRevision,
                                                      SemanticDtos.DiscoverConceptsResponse response) {
        SemanticDtos.DiscoverConceptsResponse required = schemaValidator.discoverConcepts(response);
        discoveryScope(expectedRepositoryId, expectedRevision, required.repoId(), required.analyzedRevision());
        RepositoryId repositoryId = expectedRepositoryId;
        RepositoryRevision revision = expectedRevision;
        List<AnalysisCandidate> candidates = new ArrayList<>();
        for (SemanticDtos.ConceptCandidateResponse candidate : required.candidates()) {
            addConceptCandidate(repositoryId, revision, candidate, candidates);
        }
        addFollowUps(repositoryId, revision, required.availableFollowUps(), candidates);
        List<CapabilityObservation> observations = new ArrayList<>(pageObservations(required.page(),
                required.coverage().status()));
        addLimitations(observations, required.limitations(), "concept limitation");
        addIssueSummaries(observations, required.issueSummaries(), "concept issue");
        addUnavailableFollowUps(observations, required.unavailableFollowUps(), "concept follow-up");
        return succeeded(candidates, List.of(), List.copyOf(observations));
    }

    /** 投影 provider 的精確概念 resolve 結果 */
    public CapabilityExecutionResult resolveConcept(RepositoryId expectedRepositoryId, RepositoryRevision expectedRevision,
                                                    SemanticDtos.ResolveConceptResponse response) {
        SemanticDtos.ResolveConceptResponse required = schemaValidator.resolveConcept(response);
        discoveryScope(expectedRepositoryId, expectedRevision, required.repoId(), required.analyzedRevision());
        RepositoryId repositoryId = expectedRepositoryId;
        RepositoryRevision revision = expectedRevision;
        List<AnalysisCandidate> candidates = new ArrayList<>();
        addConceptCandidate(repositoryId, revision, required.candidate(), candidates);
        return succeeded(candidates, List.of(), List.of());
    }

    /** 投影 provider 的事件監聽器探索結果 */
    public CapabilityExecutionResult discoverEventListeners(RepositoryId expectedRepositoryId,
                                                            RepositoryRevision expectedRevision,
                                                            SemanticDtos.DiscoverEventListenersResponse response) {
        SemanticDtos.DiscoverEventListenersResponse required = schemaValidator.discoverEventListeners(response);
        discoveryScope(expectedRepositoryId, expectedRevision, required.repoId(), required.analyzedRevision());
        RepositoryId repositoryId = expectedRepositoryId;
        RepositoryRevision revision = expectedRevision;
        List<AnalysisCandidate> candidates = new ArrayList<>();
        for (SemanticDtos.EventListenerCandidateResponse candidate : required.candidates()) {
            candidates.add(new SemanticTargetCandidate(repositoryId, revision, semanticTarget(candidate.target()),
                    "event listener " + candidate.target().methodName()));
            addFollowUps(repositoryId, revision, candidate.availableFollowUps(), candidates);
        }
        addFollowUps(repositoryId, revision, required.availableFollowUps(), candidates);
        List<CapabilityObservation> observations = new ArrayList<>(pageObservations(required.page(), "COMPLETE"));
        for (SemanticDtos.ListenerObservationSummaryResponse summary : required.observationSummaries()) {
            List<AnalysisCandidate> sampleCandidates = new ArrayList<>();
            int sampleIndex = 0;
            for (SemanticDtos.SourceRangePayload sample : summary.samples()) {
                sampleIndex++;
                SemanticTargetCandidate sampleCandidate = new SemanticTargetCandidate(repositoryId, revision,
                        sourceTarget(sample), "listener observation " + summary.code() + " sample=" + sampleIndex);
                candidates.add(sampleCandidate);
                sampleCandidates.add(sampleCandidate);
            }
            observations.add(observation(ObservationCode.UNRESOLVED_CALL, "listener observation " + summary.code()
                    + " count=" + summary.totalCount(), List.copyOf(sampleCandidates)));
        }
        return succeeded(candidates, List.of(), List.copyOf(observations));
    }

    /** 投影 provider 的方法實作探索結果 */
    public CapabilityExecutionResult discoverMethodImplementations(RepositoryId expectedRepositoryId,
                                                                    RepositoryRevision expectedRevision,
            SemanticDtos.DiscoverMethodImplementationsResponse response) {
        SemanticDtos.DiscoverMethodImplementationsResponse required = schemaValidator.discoverMethodImplementations(response);
        discoveryScope(expectedRepositoryId, expectedRevision, required.repoId(), required.revision());
        RepositoryId repositoryId = expectedRepositoryId;
        RepositoryRevision revision = expectedRevision;
        List<AnalysisCandidate> candidates = new ArrayList<>();
        List<EvidenceRef> evidence = new ArrayList<>();
        for (SemanticDtos.MethodImplementationCandidateResponse candidate : required.candidates()) {
            SemanticTarget target = semanticTarget(candidate.target());
            candidates.add(new SemanticTargetCandidate(repositoryId, revision, target,
                    "method implementation " + candidate.target().methodName()));
            evidence.add(metadataEvidenceMapper.implementation(repositoryId, revision, required.requestedTarget(), candidate,
                    target));
            addFollowUps(repositoryId, revision, candidate.availableFollowUps(), candidates);
        }
        List<CapabilityObservation> observations = new ArrayList<>();
        if (required.limits().truncated()) {
            observations.add(observation(ObservationCode.TRUNCATED_CANDIDATES, "method implementation limits returned="
                    + required.limits().returnedCount() + " total=" + required.limits().totalCount(), List.of()));
        }
        if ("PARTIAL".equals(required.resolution().status())) {
            observations.add(observation(ObservationCode.TRUNCATED_CANDIDATES,
                    "method implementation resolution status=PARTIAL", List.of()));
        }
        addIssueSummaries(observations, required.resolution().issueSummaries(), "method implementation issue");
        return succeeded(candidates, evidence, List.copyOf(observations));
    }

    /** 投影 provider 的型別成員探索結果 */
    public CapabilityExecutionResult discoverTypeMembers(RepositoryId expectedRepositoryId, RepositoryRevision expectedRevision,
                                                         SemanticDtos.DiscoverTypeMembersResponse response) {
        SemanticDtos.DiscoverTypeMembersResponse required = schemaValidator.discoverTypeMembers(response);
        discoveryScope(expectedRepositoryId, expectedRevision, required.repoId(), required.analyzedRevision());
        RepositoryId repositoryId = expectedRepositoryId;
        RepositoryRevision revision = expectedRevision;
        List<AnalysisCandidate> candidates = new ArrayList<>();
        List<CapabilityObservation> observations = new ArrayList<>(pageObservations(required.page(),
                required.coverage().status()));
        for (SemanticDtos.TypeMemberResponse member : required.members()) {
            if (member instanceof SemanticDtos.MethodTypeMemberResponse method) {
                candidates.add(new SemanticTargetCandidate(repositoryId, revision, semanticTarget(method.target()),
                        "type member " + method.target().methodName()));
            } else if (member instanceof SemanticDtos.FieldTypeMemberResponse field) {
                addLimitations(observations, field.limitations(), "type member field limitation");
            }
            addFollowUps(repositoryId, revision, member.availableFollowUps(), candidates);
        }
        addFollowUps(repositoryId, revision, required.availableFollowUps(), candidates);
        return succeeded(candidates, List.of(), List.copyOf(observations));
    }

    /** 投影 provider 的內部 reference 結果 */
    public CapabilityExecutionResult findInternalReferences(RepositoryId expectedRepositoryId, RepositoryRevision expectedRevision,
                                                            SemanticDtos.FindInternalReferencesResponse response) {
        SemanticDtos.FindInternalReferencesResponse required = schemaValidator.findInternalReferences(response);
        discoveryScope(expectedRepositoryId, expectedRevision, required.repoId(), required.analyzedRevision());
        RepositoryId repositoryId = expectedRepositoryId;
        RepositoryRevision revision = expectedRevision;
        List<AnalysisCandidate> candidates = new ArrayList<>();
        List<EvidenceRef> evidence = new ArrayList<>();
        List<CapabilityObservation> observations = new ArrayList<>(pageObservations(required.page(), "COMPLETE"));
        SemanticDtos.InternalReferenceTargetDeclarationResponse declaration = required.targetDeclaration();
        SemanticTarget declarationTarget = internalReferenceTarget(declaration.target(), declaration.declarationRange());
        candidates.add(new SemanticTargetCandidate(repositoryId, revision, declarationTarget, "internal reference declaration"));
        evidence.add(metadataEvidenceMapper.declaration(repositoryId, revision, declaration, required.totalReferenceCount(),
                declarationTarget));
        addFollowUps(repositoryId, revision, declaration.availableFollowUps(), candidates);
        for (SemanticDtos.ReferenceGroupResponse group : required.referenceGroups()) {
            if (group.limits().truncated()) {
                observations.add(observation(ObservationCode.TRUNCATED_CANDIDATES, "internal reference group returned="
                        + group.limits().returnedCount() + " total=" + group.limits().totalCount(), List.of()));
            }
            addUnavailableFollowUps(observations, group.unavailableFollowUps(), "internal reference follow-up");
            addFollowUps(repositoryId, revision, group.availableFollowUps(), candidates);
            for (SemanticDtos.ReferenceOccurrenceResponse occurrence : group.representativeReferences()) {
                SemanticTarget occurrenceTarget = internalReferenceTarget(group.context(), occurrence.range());
                candidates.add(new SemanticTargetCandidate(repositoryId, revision, occurrenceTarget,
                        "internal reference occurrence"));
                evidence.add(metadataEvidenceMapper.occurrence(repositoryId, revision, group, required.totalReferenceCount(),
                        occurrence, occurrenceTarget));
                addFollowUps(repositoryId, revision, occurrence.availableFollowUps(), candidates);
            }
        }
        addFollowUps(repositoryId, revision, required.availableFollowUps(), candidates);
        if ("PARTIAL".equals(required.status())) {
            observations.add(observation(ObservationCode.TRUNCATED_CANDIDATES,
                    "internal reference status=PARTIAL", List.of()));
        }
        addIssueSummaries(observations, required.issueSummaries(), "internal reference issue");
        return succeeded(candidates, evidence, List.copyOf(observations));
    }

    /** 投影 provider 的 evidence source 結果 */
    public CapabilityExecutionResult getEvidenceSource(RepositoryId expectedRepositoryId, RepositoryRevision expectedRevision,
                                                       SemanticDtos.EvidenceSourceResponse response) {
        SemanticDtos.EvidenceSourceResponse required = schemaValidator.evidenceSource(response);
        discoveryScope(expectedRepositoryId, expectedRevision, required.repoId(), required.analyzedRevision());
        return sourceResult(expectedRepositoryId, expectedRevision, required.location(), required.segment(),
                required.availableFollowUps());
    }

    /** 投影 provider 的方法來源結果 */
    public CapabilityExecutionResult getMethodSource(RepositoryId expectedRepositoryId, RepositoryRevision expectedRevision,
                                                     SemanticDtos.MethodSourceResponse response) {
        SemanticDtos.MethodSourceResponse required = schemaValidator.methodSource(response);
        discoveryScope(expectedRepositoryId, expectedRevision, required.repoId(), required.analyzedRevision());
        return sourceResult(expectedRepositoryId, expectedRevision, required.declarationLocation(), required.segment(),
                required.availableFollowUps());
    }

    /** 投影 provider 的 bounded source segment 結果 */
    public CapabilityExecutionResult getSourceSegment(RepositoryId expectedRepositoryId, RepositoryRevision expectedRevision,
                                                      SemanticDtos.SourceSegmentResponse response) {
        SemanticDtos.SourceSegmentResponse required = schemaValidator.sourceSegment(response);
        discoveryScope(expectedRepositoryId, expectedRevision, required.repoId(), required.analyzedRevision());
        CapabilityExecutionResult.Succeeded result = sourceResult(expectedRepositoryId, expectedRevision,
                required.segment().location(), required.segment(), required.availableFollowUps());
        if (!required.contextTruncated()) {
            return result;
        }
        return succeeded(result.discoveredCandidates(), result.evidence(), List.of(observation(
                ObservationCode.TRUNCATED_CANDIDATES, "source segment context is truncated", List.of())));
    }

    /** 投影 provider 的 source symbol resolve 結果 */
    public CapabilityExecutionResult resolveSourceSymbol(RepositoryId expectedRepositoryId, RepositoryRevision expectedRevision,
                                                         SemanticDtos.ResolveSourceSymbolResponse response) {
        SemanticDtos.ResolveSourceSymbolResponse required = schemaValidator.resolveSourceSymbol(response);
        discoveryScope(expectedRepositoryId, expectedRevision, required.repoId(), required.analyzedRevision());
        RepositoryId repositoryId = expectedRepositoryId;
        RepositoryRevision revision = expectedRevision;
        List<AnalysisCandidate> candidates = new ArrayList<>();
        List<CapabilityObservation> observations = new ArrayList<>();
        for (SemanticDtos.SourceContextCandidateResponse context : required.contextCandidates()) {
            addFollowUp(repositoryId, revision, context.retry(), candidates);
        }
        for (SemanticDtos.SourceSymbolCandidateResponse candidate : required.candidates()) {
            if (candidate instanceof SemanticDtos.MethodSourceSymbolCandidateResponse method) {
                candidates.add(new SemanticTargetCandidate(repositoryId, revision, semanticTarget(method.target()),
                        "source symbol " + method.target().methodName()));
            } else {
                addSourceSymbolCandidate(repositoryId, revision, candidate, candidates);
            }
            addFollowUps(repositoryId, revision, candidate.availableFollowUps(), candidates);
        }
        if (required.contextCandidateLimits().truncated()) {
            observations.add(observation(ObservationCode.TRUNCATED_CANDIDATES, "source symbol context returned="
                    + required.contextCandidateLimits().returnedCount() + " total="
                    + required.contextCandidateLimits().totalCount(), List.of()));
        }
        if (Set.of("AMBIGUOUS_CONTEXT", "AMBIGUOUS_SYMBOL", "AMBIGUOUS_OCCURRENCE").contains(required.status())) {
            observations.add(observation(ObservationCode.AMBIGUOUS_SEMANTIC_TARGET,
                    "source symbol status=" + required.status(), List.of()));
        }
        if (Set.of("CONTEXT_NOT_FOUND", "SYMBOL_NOT_FOUND", "UNRESOLVED_BINDING", "POSITION_MISMATCH")
                .contains(required.status())) {
            observations.add(observation(ObservationCode.UNRESOLVED_CALL,
                    "source symbol status=" + required.status(), List.of()));
        }
        for (SemanticDtos.SourceSymbolIssueSummaryResponse issue : required.issues()) {
            observations.add(observation(ObservationCode.UNRESOLVED_CALL, "source symbol issue " + issue.code()
                    + " count=" + issue.count(), List.of()));
        }
        return succeeded(candidates, List.of(), List.copyOf(observations));
    }

    private CapabilityExecutionResult.Succeeded sourceResult(RepositoryId repositoryId, RepositoryRevision revision,
                                                             SemanticDtos.SourceRangePayload location,
                                                             SemanticDtos.SourceSegmentPayload segment,
                                                             List<SemanticDtos.AvailableFollowUp> followUps) {
        String content = description(segment.content(), "source content");
        SemanticTarget target = sourceTarget(location);
        EvidenceRef evidence = new EvidenceRef(SOURCE_SERVICE, repositoryId, revision, target, content, List.of(),
                JavaSemanticArtifactDigest.fromContent(content));
        List<AnalysisCandidate> candidates = new ArrayList<>();
        addFollowUps(repositoryId, revision, followUps, candidates);
        return succeeded(candidates, List.of(evidence), List.of());
    }

    private void addLimitations(List<CapabilityObservation> observations, List<String> limitations, String subject) {
        for (String limitation : limitations) {
            observations.add(observation(ObservationCode.UNSUPPORTED_CLAIM, subject + " " + limitation, List.of()));
        }
    }

    private void addIssueSummaries(List<CapabilityObservation> observations,
                                   List<SemanticDtos.IssueSummaryResponse> summaries, String subject) {
        for (SemanticDtos.IssueSummaryResponse summary : summaries) {
            observations.add(observation(ObservationCode.UNRESOLVED_CALL, subject + " " + summary.code()
                    + " count=" + summary.count(), List.of()));
        }
    }

    private void addUnavailableFollowUps(List<CapabilityObservation> observations,
                                         List<SemanticDtos.UnavailableFollowUpResponse> unavailableFollowUps,
                                         String subject) {
        for (SemanticDtos.UnavailableFollowUpResponse unavailable : unavailableFollowUps) {
            observations.add(observation(ObservationCode.UNADDRESSED_PART, subject + " " + unavailable.reason()
                    + " action=" + unavailable.recommendedAction(), List.of()));
        }
    }

    private void addInternalReferenceCandidate(RepositoryId repositoryId, RepositoryRevision revision,
                                               SemanticDtos.InternalReferenceFollowUpTarget target,
                                               SemanticDtos.TextRangePayload range, String description,
                                               List<AnalysisCandidate> candidates) {
        candidates.add(new SemanticTargetCandidate(repositoryId, revision, internalReferenceTarget(target, range), description));
    }

    private void addInternalReferenceCandidate(RepositoryId repositoryId, RepositoryRevision revision,
                                               SemanticDtos.InternalReferenceContextResponse context,
                                               SemanticDtos.TextRangePayload range, String description,
                                               List<AnalysisCandidate> candidates) {
        candidates.add(new SemanticTargetCandidate(repositoryId, revision, internalReferenceTarget(context, range), description));
    }

    private SemanticTarget internalReferenceTarget(SemanticDtos.InternalReferenceFollowUpTarget target,
                                                   SemanticDtos.TextRangePayload range) {
        return sourceTarget(new SemanticDtos.SourceRangePayload(sourceFile(target.identity()), range));
    }

    private SemanticTarget internalReferenceTarget(SemanticDtos.InternalReferenceContextResponse context,
                                                   SemanticDtos.TextRangePayload range) {
        String sourceFile;
        if (context instanceof SemanticDtos.InternalReferenceTypeContextResponse type) {
            sourceFile = type.sourceType().sourceFile();
        } else if (context instanceof SemanticDtos.InternalReferenceMethodContextResponse method) {
            sourceFile = method.method().sourceType().sourceFile();
        } else {
            throw contract("unsupported internal reference context");
        }
        return sourceTarget(new SemanticDtos.SourceRangePayload(sourceFile, range));
    }

    private void addSourceSymbolCandidate(RepositoryId repositoryId, RepositoryRevision revision,
                                          SemanticDtos.SourceSymbolCandidateResponse candidate,
                                          List<AnalysisCandidate> candidates) {
        String sourceFile;
        if (candidate instanceof SemanticDtos.VariableLikeSourceSymbolCandidateResponse variable) {
            sourceFile = sourceFile(variable.identity());
        } else if (candidate instanceof SemanticDtos.StaticConstantSourceSymbolCandidateResponse constant) {
            sourceFile = sourceFile(constant.identity());
        } else if (candidate instanceof SemanticDtos.SourceTypeSymbolCandidateResponse type) {
            sourceFile = type.identity().sourceFile();
        } else {
            throw contract("unsupported source symbol candidate");
        }
        candidates.add(new SemanticTargetCandidate(repositoryId, revision,
                sourceTarget(new SemanticDtos.SourceRangePayload(sourceFile, candidate.declarationRange())),
                "source symbol " + candidate.kind() + " declaration"));
        candidates.add(new SemanticTargetCandidate(repositoryId, revision,
                sourceTarget(new SemanticDtos.SourceRangePayload(sourceFile, candidate.representativeOccurrence())),
                "source symbol " + candidate.kind() + " occurrence"));
    }

    private String sourceFile(SemanticDtos.InternalReferenceIdentity identity) {
        if (identity instanceof SemanticDtos.SourceTypeIdentityPayload type) {
            return type.sourceFile();
        }
        if (identity instanceof SemanticDtos.MethodTargetPayload method) {
            return method.sourceType().sourceFile();
        }
        if (identity instanceof SemanticDtos.SourceMemberIdentityPayload.TypeMember member) {
            return member.ownerType().sourceFile();
        }
        if (identity instanceof SemanticDtos.SourceMemberIdentityPayload.MethodScoped member) {
            return member.declaringMethod().sourceType().sourceFile();
        }
        throw contract("unsupported internal reference identity");
    }

    private void addConceptCandidate(RepositoryId repositoryId, RepositoryRevision revision,
                                     SemanticDtos.ConceptCandidateResponse candidate,
                                     List<AnalysisCandidate> candidates) {
        candidate.identity().target().ifPresent(target -> candidates.add(new SemanticTargetCandidate(repositoryId, revision,
                semanticTarget(target), description(candidate.displayValue(), "structured concept"))));
        for (SemanticDtos.ConceptEvidenceResponse evidence : candidate.evidence()) {
            evidence.identity().target().ifPresent(target -> candidates.add(new SemanticTargetCandidate(repositoryId, revision,
                    semanticTarget(target), "concept evidence")));
        }
        candidate.details().ifPresent(details -> addConceptDetailsFollowUps(repositoryId, revision, details, candidates));
        addFollowUps(repositoryId, revision, candidate.availableFollowUps(), candidates);
    }

    private void addConceptDetailsFollowUps(RepositoryId repositoryId, RepositoryRevision revision,
                                            SemanticDtos.ConceptCandidateDetailsResponse details,
                                            List<AnalysisCandidate> candidates) {
        if (details instanceof SemanticDtos.MapperStatementConceptCandidateDetailsResponse mapper) {
            for (SemanticDtos.MapperSourceMethodCandidateResponse candidate : mapper.mapping().candidates()) {
                candidates.add(new SemanticTargetCandidate(repositoryId, revision, semanticTarget(candidate.target()),
                        "mapper source method " + candidate.target().methodName()));
                addFollowUps(repositoryId, revision, candidate.availableFollowUps(), candidates);
            }
        }
    }

    private void discoveryScope(RepositoryId expectedRepositoryId, RepositoryRevision expectedRevision,
                                String responseRepositoryId, String responseRevision) {
        RepositoryId requiredRepositoryId = requireProviderObject(expectedRepositoryId, "expected repository ID");
        RepositoryRevision requiredRevision = requireProviderObject(expectedRevision, "expected repository revision");
        if (!requiredRepositoryId.value().equals(responseRepositoryId)
                || !requiredRevision.value().equals(responseRevision)) {
            throw contract("discovery response scope does not match the selected candidate");
        }
    }

    private void addFollowUps(RepositoryId repositoryId, RepositoryRevision revision,
                              List<SemanticDtos.AvailableFollowUp> followUps, List<AnalysisCandidate> candidates) {
        for (SemanticDtos.AvailableFollowUp followUp : followUps) {
            addFollowUp(repositoryId, revision, followUp, candidates);
        }
    }

    private void addFollowUp(RepositoryId repositoryId, RepositoryRevision revision,
                             SemanticDtos.AvailableFollowUp followUp, List<AnalysisCandidate> candidates) {
        FollowUpCandidate candidate = followUpMapper.map(repositoryId, revision, followUp);
        candidates.add(candidate);
    }

    private SemanticTarget semanticTarget(SemanticDtos.MethodTargetPayload target) {
        return semanticTarget(new SemanticDtos.MethodTarget(target.sourceType().sourceFile(),
                target.sourceType().javaType().packageName(), target.sourceType().javaType().className(),
                target.methodName(), target.parameterTypes()));
    }

    private SemanticTarget sourceTarget(SemanticDtos.SourceRangePayload location) {
        return targetMapper.semanticTarget(location);
    }

    private List<CapabilityObservation> pageObservations(SemanticDtos.PageResponse page, String coverageStatus) {
        List<CapabilityObservation> observations = new ArrayList<>();
        if (page.hasMore()) {
            observations.add(observation(ObservationCode.TRUNCATED_CANDIDATES, "additional results are available", List.of()));
        }
        if ("PARTIAL".equals(coverageStatus)) {
            observations.add(observation(ObservationCode.MISSING_SOURCE, "provider coverage is partial", List.of()));
        }
        return List.copyOf(observations);
    }

    private CapabilityExecutionResult callGraph(RepositoryId repositoryId, RepositoryRevision expectedRevision,
                                                SemanticDtos.MethodTargetPayload requestedTarget, String status,
                                                String analyzedRevision,
                                                String rootNodeId, SemanticDtos.GraphTraversal traversal,
                                                List<SemanticDtos.GraphNode> nodes, List<SemanticDtos.GraphEdge> edges,
                                                List<SemanticDtos.GraphWarning> warnings,
                                                List<SemanticDtos.GraphError> errors) {
        Objects.requireNonNull(repositoryId, "graph repository ID must not be null");
        Objects.requireNonNull(expectedRevision, "graph expected revision must not be null");
        Objects.requireNonNull(requestedTarget, "graph requested target must not be null");
        schemaValidator.graph(status, analyzedRevision, rootNodeId, traversal, nodes, edges, warnings, errors);
        if (!expectedRevision.value().equals(analyzedRevision)) {
            throw contract("graph response revision does not match the expected revision");
        }
        RepositoryRevision revision = new RepositoryRevision(analyzedRevision);
        List<SemanticDtos.GraphNode> requiredNodes = requiredList(nodes, "graph node");
        List<SemanticDtos.GraphEdge> requiredEdges = requiredList(edges, "graph edge");
        List<SemanticDtos.GraphWarning> requiredWarnings = requiredList(warnings, "graph warning");
        List<SemanticDtos.GraphError> requiredErrors = requiredList(errors, "graph error");
        List<AnalysisCandidate> candidates = new ArrayList<>();
        List<AnalysisCandidate> edgeCandidates = new ArrayList<>();
        List<CapabilityObservation> observations = new ArrayList<>();
        SemanticDtos.GraphNode root = responseRoot(rootNodeId, requiredNodes);
        if (!matches(requestedTarget, root.target())) {
            throw contract("graph response root target does not match the requested target");
        }
        for (SemanticDtos.GraphNode node : requiredNodes) {
            if (Objects.nonNull(node.target())) {
                candidates.add(new SemanticTargetCandidate(repositoryId, revision, semanticTarget(node.target()),
                        description("graph node " + node.nodeId(), "graph node")));
            }
            addNodeObservation(node, observations);
        }
        for (SemanticDtos.GraphEdge edge : requiredEdges) {
            if ("UNRESOLVED_GUESS".equals(edge.category())) {
                observations.add(observation(ObservationCode.UNRESOLVED_CALL, edge.callExpression(), List.of()));
            }
            if ("RESOLVED_OPAQUE".equals(edge.category())) {
                observations.add(observation(ObservationCode.OPAQUE_EXTERNAL_CALL, edge.callExpression(), List.of()));
            }
            addFollowUps(repositoryId, revision, edge.availableFollowUps(), edgeCandidates);
        }
        candidates.addAll(new LinkedHashSet<>(edgeCandidates));
        for (SemanticDtos.GraphWarning warning : requiredWarnings) {
            ObservationCode code = switch (warning.code()) {
                case "DESCENDANT_CALL_AMBIGUOUS" -> ObservationCode.AMBIGUOUS_SEMANTIC_TARGET;
                case "DESCENDANT_CALL_UNRESOLVED" -> ObservationCode.UNRESOLVED_CALL;
                case "INCOMING_CALLER_REJECTED" -> ObservationCode.MISSING_SOURCE;
                case "NODE_BUDGET_REACHED" -> ObservationCode.PARTIAL_GRAPH;
                default -> throw contract("unsupported graph warning code");
            };
            List<AnalysisCandidate> warningCandidates = targetCandidates(repositoryId, revision,
                    requiredList(warning.candidates(), "graph warning candidate"), warning.message());
            candidates.addAll(warningCandidates);
            observations.add(observation(code, warning.message(), warningCandidates));
        }
        for (SemanticDtos.GraphError error : requiredErrors) {
            observations.add(observation(ObservationCode.UNRESOLVED_CALL, error.message(), List.of()));
        }
        if ("PARTIAL".equals(status)) {
            observations.add(observation(ObservationCode.PARTIAL_GRAPH, "Java Semantic Service returned a partial graph",
                    List.of()));
        }
        String content = graphContent(rootNodeId, traversal, requiredNodes, requiredEdges, requiredWarnings, requiredErrors);
        EvidenceRef evidence = new EvidenceRef(SOURCE_SERVICE, repositoryId, revision, semanticTarget(root.target()), content,
                evidenceWarnings(requiredWarnings, requiredErrors), JavaSemanticArtifactDigest.fromContent(content));
        return succeeded(candidates, List.of(evidence), observations);
    }

    private boolean matches(SemanticDtos.MethodTargetPayload requested, SemanticDtos.MethodTarget response) {
        return requested.sourceType().sourceFile().equals(response.sourceFile())
                && requested.sourceType().javaType().packageName().equals(response.packageName())
                && requested.sourceType().javaType().className().equals(response.className())
                && requested.methodName().equals(response.methodName())
                && requested.parameterTypes().equals(response.parameterTypes());
    }

    private void addResolutionCandidates(RepositoryId repositoryId, RepositoryRevision revision,
                                         SemanticDtos.MethodTargetResolutionResponse resolution, String description,
                                         List<AnalysisCandidate> candidates,
                                         List<CapabilityObservation> observations) {
        requireProviderObject(resolution, "method target resolution");
        String status = resolution.status();
        if ("RESOLVED".equals(status)) {
            SemanticDtos.MethodTarget target = requireProviderObject(resolution.target(), "resolved target");
            candidates.add(new SemanticTargetCandidate(repositoryId, revision, semanticTarget(target), description));
            return;
        }
        if ("AMBIGUOUS".equals(status)) {
            List<AnalysisCandidate> ambiguousCandidates = targetCandidates(repositoryId, revision,
                    requiredList(resolution.candidates(), "ambiguous method target"), description);
            candidates.addAll(ambiguousCandidates);
            observations.add(observation(ObservationCode.AMBIGUOUS_SEMANTIC_TARGET, resolution.reasonCode(),
                    ambiguousCandidates));
            return;
        }
        if ("UNRESOLVED".equals(status)) {
            observations.add(observation(ObservationCode.UNRESOLVED_CALL, resolution.reasonCode(), List.of()));
            return;
        }
        throw contract("unsupported method target resolution status");
    }

    private List<AnalysisCandidate> targetCandidates(RepositoryId repositoryId, RepositoryRevision revision,
                                                     List<SemanticDtos.MethodTarget> targets, String description) {
        List<AnalysisCandidate> candidates = new ArrayList<>();
        for (SemanticDtos.MethodTarget target : targets) {
            candidates.add(new SemanticTargetCandidate(repositoryId, revision, semanticTarget(target),
                    description(description, "semantic target")));
        }
        return List.copyOf(candidates);
    }

    private void addNodeObservation(SemanticDtos.GraphNode node, List<CapabilityObservation> observations) {
        String contentState = node.contentState();
        String traversalState = node.traversalState();
        if ("EXTERNAL".equals(contentState) || "EXTERNAL".equals(traversalState)
                || "OPAQUE".equals(traversalState)) {
            observations.add(observation(ObservationCode.OPAQUE_EXTERNAL_CALL, "graph node " + node.nodeId(), List.of()));
        }
        if ("TARGET_ONLY".equals(contentState) || "BUDGET_CUTOFF".equals(traversalState)) {
            observations.add(observation(ObservationCode.MISSING_SOURCE, "graph node " + node.nodeId(), List.of()));
        }
    }

    private SemanticTargetCandidate selectedTarget(CapabilityInvocation invocation) {
        List<IssuedCandidate> selected = invocation.candidates();
        if (selected.size() != 1 || !(selected.getFirst().candidate() instanceof SemanticTargetCandidate candidate)) {
            throw contract("call graph requires exactly one semantic target candidate");
        }
        return candidate;
    }

    private CapabilityExecutionResult.Succeeded succeeded(List<AnalysisCandidate> candidates, List<EvidenceRef> evidence,
                                                           List<CapabilityObservation> observations) {
        return new CapabilityExecutionResult.Succeeded(candidates, evidence, observations);
    }

    private CapabilityObservation observation(ObservationCode code, String description,
                                              List<AnalysisCandidate> candidates) {
        return new CapabilityObservation(code, description(description, "provider observation"), candidates, List.of(),
                SOURCE_SERVICE);
    }

    private List<EvidenceWarning> evidenceWarnings(List<SemanticDtos.GraphWarning> warnings,
                                                   List<SemanticDtos.GraphError> errors) {
        List<EvidenceWarning> result = new ArrayList<>();
        for (SemanticDtos.GraphWarning warning : warnings) {
            result.add(new EvidenceWarning(warning.code(), description(warning.message(), "graph warning")));
        }
        for (SemanticDtos.GraphError error : errors) {
            result.add(new EvidenceWarning(error.code(), description(error.message(), "graph error")));
        }
        return List.copyOf(result);
    }

    private String graphContent(String rootNodeId, SemanticDtos.GraphTraversal traversal,
                                List<SemanticDtos.GraphNode> nodes, List<SemanticDtos.GraphEdge> edges,
                                List<SemanticDtos.GraphWarning> warnings, List<SemanticDtos.GraphError> errors) {
        List<String> parts = new ArrayList<>();
        parts.add("root=" + rootNodeId);
        parts.add("traversal=" + traversal.requestedDepth() + "/" + traversal.expandedNodeCount() + "/"
                + traversal.nodeBudget() + "/" + traversal.rootDirectCallsComplete() + "/" + traversal.limitReason());
        for (SemanticDtos.GraphNode node : nodes) {
            parts.add("node=" + node.nodeId() + ":" + node.contentState() + ":" + node.traversalState() + ":"
                    + node.dispatchKind() + ":target=" + targetSummary(node.target()) + ":external="
                    + providerText(node.externalSymbol()) + ":range=" + textRangeSummary(node.declarationRange()));
        }
        for (SemanticDtos.GraphEdge edge : edges) {
            parts.add("edge=" + edge.callerNodeId() + ">" + edge.calleeNodeId() + ":" + edge.category() + ":"
                    + edge.resolutionStrategy() + ":" + singleLine(edge.callExpression()) + ":evidence="
                    + summaries(edge.evidence()) + ":call-site=" + sourceRangeSummary(edge.callSite()));
        }
        for (SemanticDtos.GraphWarning warning : warnings) {
            parts.add("warning=" + warning.code() + ":"
                    + singleLine(warning.message()) + ":node=" + warning.nodeId() + ":call="
                    + providerText(warning.callExpression()) + ":call-site=" + sourceRangeSummary(warning.callSite())
                    + ":candidates=" + targetSummaries(warning.candidates()));
        }
        for (SemanticDtos.GraphError error : errors) {
            parts.add("error=" + error.code() + ":" + singleLine(error.message()) + ":node=" + error.nodeId());
        }
        return String.join("; ", parts);
    }

    private SemanticDtos.GraphNode responseRoot(String rootNodeId, List<SemanticDtos.GraphNode> nodes) {
        SemanticDtos.GraphNode root = null;
        for (SemanticDtos.GraphNode node : nodes) {
            if (rootNodeId.equals(node.nodeId())) {
                if (Objects.nonNull(root)) {
                    throw contract("graph response must contain exactly one root node");
                }
                root = node;
            }
        }
        SemanticDtos.GraphNode requiredRoot = requireProviderObject(root, "graph root node");
        if (Objects.isNull(requiredRoot.target())) {
            throw contract("graph root node target must not be null");
        }
        return requiredRoot;
    }

    private String targetSummary(SemanticDtos.MethodTarget target) {
        if (Objects.isNull(target)) {
            return "null";
        }
        return singleLine(target.sourceFile()) + "#" + singleLine(target.className()) + "." + singleLine(target.methodName());
    }

    private String summaries(List<String> values) {
        List<String> summaries = new ArrayList<>();
        for (String value : values) {
            summaries.add(singleLine(value));
        }
        return String.join(",", summaries);
    }

    private String targetSummaries(List<SemanticDtos.MethodTarget> targets) {
        List<String> summaries = new ArrayList<>();
        for (SemanticDtos.MethodTarget target : targets) {
            summaries.add(targetSummary(target));
        }
        return String.join(",", summaries);
    }

    private String sourceRangeSummary(SemanticDtos.SourceRangePayload range) {
        if (Objects.isNull(range)) {
            return "null";
        }
        return singleLine(range.sourceFile()) + "@" + textRangeSummary(range.range());
    }

    private String textRangeSummary(SemanticDtos.TextRangePayload range) {
        if (Objects.isNull(range)) {
            return "null";
        }
        return range.start().line() + ":" + range.start().character()
                + "-" + range.end().line() + ":" + range.end().character();
    }

    private String providerText(String value) {
        return Objects.isNull(value) ? "null" : singleLine(value);
    }

    private static String description(String value, String fallback) {
        String sanitized = singleLine(value);
        return StringUtils.hasText(sanitized) ? sanitized : fallback;
    }

    static String singleLine(String value) {
        String required = requireProviderObject(value, "provider text");
        String normalized = required.replaceAll("\\s+", " ").trim();
        return normalized.length() <= MAX_TEXT_LENGTH ? normalized : normalized.substring(0, MAX_TEXT_LENGTH);
    }

    static String failureDescription(String value) {
        String sanitized = singleLine(value);
        return sanitized.length() <= 500 ? sanitized : sanitized.substring(0, 500);
    }

    private static String requiredText(String value, String description) {
        if (!StringUtils.hasText(value)) {
            throw contract(description + " must be nonblank");
        }
        return value.trim();
    }

    private static <T> List<T> requiredList(List<T> values, String description) {
        if (Objects.isNull(values)) {
            throw contract(description + " list must not be null");
        }
        List<T> copied = new ArrayList<>();
        for (T value : values) {
            copied.add(requireProviderObject(value, description + " must not contain null values"));
        }
        return List.copyOf(copied);
    }

    private static <T> T requireProviderObject(T value, String description) {
        if (Objects.isNull(value)) {
            throw contract(description + " must not be null");
        }
        return value;
    }

    private static CapabilityExecutionContractException contract(String message) {
        return new CapabilityExecutionContractException(message);
    }
}
