package com.java.system.agent.answering.application;

import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.AnalysisCandidate;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.candidate.RepositoryCandidate;
import com.java.system.agent.answering.domain.evidence.EvidenceRef;
import com.java.system.agent.answering.domain.evidence.IssuedEvidence;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.handle.EvidenceHandle;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.observation.AgentObservation;
import com.java.system.agent.answering.domain.observation.ObservationId;
import com.java.system.agent.answering.domain.observation.ObservationSource;
import com.java.system.agent.answering.domain.observation.CapabilityObservation;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.RunAttempt;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.answering.port.out.CapabilityExecutionContractException;
import com.java.system.agent.answering.port.out.RepositoryDescriptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 將 answering 接受的 catalog 與 capability values 配發成 attempt-local opaque handles
 */
public final class ContextIssuer {

    public RunAttempt issueInitial(
            AnalysisRunId runId,
            AnalysisAttemptId attemptId,
            RevisionVector revisions,
            List<CapabilityPolicy> capabilities,
            List<RepositoryDescriptor> repositories) {
        Objects.requireNonNull(repositories, "repository catalog must not be null");
        List<RepositoryDescriptor> validatedRepositories = repositories.stream()
                .map(repository -> Objects.requireNonNull(
                        repository, "repository catalog must not contain null"))
                .toList();
        rejectDuplicateRepositoryIds(validatedRepositories);
        List<AnalysisCandidate> candidates = validatedRepositories.stream()
                .sorted(Comparator.comparing(descriptor -> descriptor.repositoryId().value()))
                .map(descriptor -> (AnalysisCandidate) new RepositoryCandidate(
                        descriptor.repositoryId(), descriptor.description()))
                .toList();
        HandleBinding binding = binding(runId, attemptId, revisions);
        return new RunAttempt(
                attemptId,
                revisions,
                issueCapabilities(binding, capabilities),
                issueCandidates(binding, candidates, 1, 1),
                Map.of(),
                Map.of());
    }

    public RunAttempt reissue(
            AnalysisRunId runId,
            RunAttempt currentAttempt,
            RevisionVector revisions) {
        Objects.requireNonNull(currentAttempt, "current run attempt must not be null");
        HandleBinding binding = binding(runId, currentAttempt.attemptId(), revisions);
        Map<CapabilityHandle, CapabilityPolicy> capabilities = new LinkedHashMap<>();
        for (Map.Entry<CapabilityHandle, CapabilityPolicy> entry
                : currentAttempt.issuedCapabilities().entrySet()) {
            CapabilityHandle handle = new CapabilityHandle(entry.getKey().value(), binding);
            capabilities.put(handle, entry.getValue());
        }
        Map<CandidateHandle, IssuedCandidate> candidates = new LinkedHashMap<>();
        Map<CandidateHandle, CandidateHandle> candidateHandles = new LinkedHashMap<>();
        for (IssuedCandidate issued : currentAttempt.issuedCandidates().values()) {
            CandidateHandle handle = new CandidateHandle(
                    issued.handle().value(), binding, issued.handle().kind());
            IssuedCandidate rebound = new IssuedCandidate(handle, issued.candidate());
            candidates.put(handle, rebound);
            candidateHandles.put(issued.handle(), handle);
        }
        Map<EvidenceHandle, IssuedEvidence> evidence = new LinkedHashMap<>();
        Map<EvidenceHandle, EvidenceHandle> evidenceHandles = new LinkedHashMap<>();
        for (IssuedEvidence issued : currentAttempt.issuedEvidence().values()) {
            EvidenceHandle handle = new EvidenceHandle(issued.handle().value(), binding);
            IssuedEvidence rebound = new IssuedEvidence(handle, issued.evidence());
            evidence.put(handle, rebound);
            evidenceHandles.put(issued.handle(), handle);
        }
        Map<ObservationId, AgentObservation> observations = new LinkedHashMap<>();
        for (AgentObservation observation : currentAttempt.observations().values()) {
            observations.put(observation.id(), new AgentObservation(
                    observation.id(),
                    observation.source(),
                    observation.code(),
                    observation.description(),
                    replaceCandidates(observation.candidateHandles(), candidateHandles),
                    replaceEvidence(observation.evidenceHandles(), evidenceHandles),
                    observation.provenance()));
        }
        return new RunAttempt(currentAttempt.attemptId(), revisions, capabilities, candidates, evidence, observations);
    }

    public CapabilityIssue issueCapabilityResult(
            AnalysisRunId runId,
            RunAttempt currentAttempt,
            CapabilityExecutionResult.Succeeded result,
            Set<RepositoryId> catalogRepositoryIds) {
        Objects.requireNonNull(currentAttempt, "current run attempt must not be null");
        Objects.requireNonNull(result, "capability execution result must not be null");
        Objects.requireNonNull(catalogRepositoryIds, "catalog repository IDs must not be null");
        validateCapabilityRepositories(result, catalogRepositoryIds);
        rejectPreviouslyIssuedCandidates(currentAttempt, result.discoveredCandidates());
        rejectPreviouslyIssuedEvidence(currentAttempt, result.evidence());
        HandleBinding binding = binding(runId, currentAttempt.attemptId(), currentAttempt.revisionVector());
        Map<CandidateHandle, IssuedCandidate> newCandidates = issueCandidates(
                binding,
                result.discoveredCandidates(),
                repositoryCount(currentAttempt) + 1,
                targetCount(currentAttempt) + 1);
        Map<EvidenceHandle, IssuedEvidence> newEvidence = issueEvidence(
                binding, result.evidence(), currentAttempt.issuedEvidence().size() + 1);
        Map<CandidateHandle, IssuedCandidate> candidates = appendDistinct(
                currentAttempt.issuedCandidates(), newCandidates, "candidate handle");
        Map<EvidenceHandle, IssuedEvidence> evidence = appendDistinct(
                currentAttempt.issuedEvidence(), newEvidence, "evidence handle");
        List<AgentObservation> observations = issueObservations(
                currentAttempt,
                result.observations(),
                newCandidates,
                newEvidence);
        RunAttempt context = new RunAttempt(
                currentAttempt.attemptId(),
                currentAttempt.revisionVector(),
                currentAttempt.issuedCapabilities(),
                candidates,
                evidence,
                currentAttempt.observations());
        return new CapabilityIssue(context, observations);
    }

    private Map<CapabilityHandle, CapabilityPolicy> issueCapabilities(
            HandleBinding binding,
            List<CapabilityPolicy> values) {
        Objects.requireNonNull(values, "capability catalog must not be null");
        List<CapabilityPolicy> sorted = values.stream()
                .map(value -> Objects.requireNonNull(value, "capability catalog must not contain null"))
                .sorted(Comparator.comparing((CapabilityPolicy capabilityPolicy) -> capabilityPolicy.name())
                        .thenComparing(capabilityPolicy -> capabilityPolicy.version()))
                .toList();
        Set<CapabilityIdentity> identities = new LinkedHashSet<>();
        for (CapabilityPolicy value : sorted) {
            CapabilityIdentity identity = new CapabilityIdentity(value.name(), value.version());
            if (!identities.add(identity)) {
                throw protocolFailure("capability catalog contains duplicate name and version");
            }
        }
        Map<CapabilityHandle, CapabilityPolicy> issued = new LinkedHashMap<>();
        int ordinal = 1;
        for (CapabilityPolicy value : sorted) {
            CapabilityHandle handle = new CapabilityHandle(prefix(binding) + "C" + ordinal, binding);
            issued.put(handle, value);
            ordinal++;
        }
        return Collections.unmodifiableMap(issued);
    }

    private Map<CandidateHandle, IssuedCandidate> issueCandidates(
            HandleBinding binding,
            List<AnalysisCandidate> values,
            int firstRepositoryOrdinal,
            int firstTargetOrdinal) {
        Objects.requireNonNull(values, "analysis candidates must not be null");
        Map<CandidateHandle, IssuedCandidate> issued = new LinkedHashMap<>();
        Set<AnalysisCandidate> distinctValues = new LinkedHashSet<>();
        int repositoryOrdinal = firstRepositoryOrdinal;
        int targetOrdinal = firstTargetOrdinal;
        for (AnalysisCandidate value : values) {
            AnalysisCandidate candidate = Objects.requireNonNull(
                    value, "analysis candidates must not contain null");
            if (!distinctValues.add(candidate)) {
                throw protocolFailure("capability result contains a duplicate candidate");
            }
            boolean repository = candidate.kind() == CandidateKind.REPOSITORY;
            String valuePrefix = repository ? "R" : "T";
            int ordinal = repository ? repositoryOrdinal++ : targetOrdinal++;
            CandidateHandle handle = new CandidateHandle(
                    prefix(binding) + valuePrefix + ordinal,
                    binding,
                    candidate.kind());
            issued.put(handle, new IssuedCandidate(handle, candidate));
        }
        return Collections.unmodifiableMap(issued);
    }

    private Map<EvidenceHandle, IssuedEvidence> issueEvidence(
            HandleBinding binding,
            List<EvidenceRef> values,
            int firstOrdinal) {
        Objects.requireNonNull(values, "capability evidence must not be null");
        Map<EvidenceHandle, IssuedEvidence> issued = new LinkedHashMap<>();
        Set<EvidenceRef> distinctValues = new LinkedHashSet<>();
        int ordinal = firstOrdinal;
        for (EvidenceRef value : values) {
            EvidenceRef evidence = Objects.requireNonNull(value, "capability evidence must not contain null");
            if (!distinctValues.add(evidence)) {
                throw protocolFailure("capability result contains duplicate evidence");
            }
            EvidenceHandle handle = new EvidenceHandle(prefix(binding) + "E" + ordinal, binding);
            issued.put(handle, new IssuedEvidence(handle, evidence));
            ordinal++;
        }
        return Collections.unmodifiableMap(issued);
    }

    private List<AgentObservation> issueObservations(
            RunAttempt currentAttempt,
            List<CapabilityObservation> rawObservations,
            Map<CandidateHandle, IssuedCandidate> newCandidates,
            Map<EvidenceHandle, IssuedEvidence> newEvidence) {
        List<AgentObservation> issued = new ArrayList<>();
        int ordinal = currentAttempt.observations().size() + 1;
        for (CapabilityObservation raw : rawObservations) {
            Set<CandidateHandle> candidateHandles = resolveCandidates(raw.candidates(), newCandidates);
            Set<EvidenceHandle> evidenceHandles = resolveEvidence(raw.evidence(), newEvidence);
            ObservationId id = new ObservationId(
                    currentAttempt.attemptId().value() + ":O" + ordinal);
            issued.add(new AgentObservation(
                    id,
                    ObservationSource.CAPABILITY_EXECUTOR,
                    raw.code(),
                    raw.description(),
                    candidateHandles,
                    evidenceHandles,
                    raw.provenance()));
            ordinal++;
        }
        return List.copyOf(issued);
    }

    private Set<CandidateHandle> resolveCandidates(
            List<AnalysisCandidate> references,
            Map<CandidateHandle, IssuedCandidate> issued) {
        Set<CandidateHandle> handles = new LinkedHashSet<>();
        for (AnalysisCandidate reference : references) {
            CandidateHandle handle = issued.values().stream()
                    .filter(candidate -> candidate.candidate().equals(reference))
                    .map(issuedCandidate -> issuedCandidate.handle())
                    .findFirst()
                    .orElseThrow(() -> protocolFailure(
                            "capability observation references a candidate outside the same result"));
            handles.add(handle);
        }
        return Set.copyOf(handles);
    }

    private Set<EvidenceHandle> resolveEvidence(
            List<EvidenceRef> references,
            Map<EvidenceHandle, IssuedEvidence> issued) {
        Set<EvidenceHandle> handles = new LinkedHashSet<>();
        for (EvidenceRef reference : references) {
            EvidenceHandle handle = issued.values().stream()
                    .filter(evidence -> evidence.evidence().equals(reference))
                    .map(issuedEvidence -> issuedEvidence.handle())
                    .findFirst()
                    .orElseThrow(() -> protocolFailure(
                            "capability observation references evidence outside the same result"));
            handles.add(handle);
        }
        return Set.copyOf(handles);
    }

    private Set<CandidateHandle> replaceCandidates(
            Set<CandidateHandle> oldHandles,
            Map<CandidateHandle, CandidateHandle> replacements) {
        Set<CandidateHandle> replaced = new LinkedHashSet<>();
        for (CandidateHandle oldHandle : oldHandles) {
            CandidateHandle replacement = replacements.get(oldHandle);
            if (Objects.isNull(replacement)) {
                throw protocolFailure("reissued observation references an unknown candidate handle");
            }
            replaced.add(replacement);
        }
        return Set.copyOf(replaced);
    }

    private Set<EvidenceHandle> replaceEvidence(
            Set<EvidenceHandle> oldHandles,
            Map<EvidenceHandle, EvidenceHandle> replacements) {
        Set<EvidenceHandle> replaced = new LinkedHashSet<>();
        for (EvidenceHandle oldHandle : oldHandles) {
            EvidenceHandle replacement = replacements.get(oldHandle);
            if (Objects.isNull(replacement)) {
                throw protocolFailure("reissued observation references an unknown evidence handle");
            }
            replaced.add(replacement);
        }
        return Set.copyOf(replaced);
    }

    private int repositoryCount(RunAttempt attempt) {
        return (int) attempt.issuedCandidates().values().stream()
                .filter(candidate -> candidate.candidate().kind() == CandidateKind.REPOSITORY)
                .count();
    }

    private int targetCount(RunAttempt attempt) {
        return attempt.issuedCandidates().size() - repositoryCount(attempt);
    }

    private void rejectDuplicateRepositoryIds(List<RepositoryDescriptor> repositories) {
        Set<RepositoryId> repositoryIds = new LinkedHashSet<>();
        for (RepositoryDescriptor repository : repositories) {
            if (!repositoryIds.add(repository.repositoryId())) {
                throw protocolFailure("repository catalog contains a duplicate repository ID");
            }
        }
    }

    void validateCapabilityRepositories(
            CapabilityExecutionResult.Succeeded result,
            Set<RepositoryId> catalogRepositoryIds) {
        Set<RepositoryId> validatedCatalogIds = new LinkedHashSet<>();
        for (RepositoryId repositoryId : catalogRepositoryIds) {
            validatedCatalogIds.add(Objects.requireNonNull(
                    repositoryId, "catalog repository IDs must not contain null"));
        }
        for (AnalysisCandidate candidate : result.discoveredCandidates()) {
            if (!validatedCatalogIds.contains(candidate.repositoryId())) {
                throw protocolFailure("capability result candidate repository is absent from the catalog");
            }
        }
        for (EvidenceRef evidence : result.evidence()) {
            if (!validatedCatalogIds.contains(evidence.repositoryId())) {
                throw protocolFailure("capability result evidence repository is absent from the catalog");
            }
        }
    }

    private void rejectPreviouslyIssuedCandidates(
            RunAttempt currentAttempt,
            List<AnalysisCandidate> candidates) {
        Set<AnalysisCandidate> previouslyIssued = currentAttempt.issuedCandidates().values().stream()
                .map(issuedCandidate -> issuedCandidate.candidate())
                .collect(Collectors.toSet());
        for (AnalysisCandidate candidate : candidates) {
            if (previouslyIssued.contains(candidate)) {
                throw protocolFailure("capability result repeats a previously issued candidate");
            }
        }
    }

    private void rejectPreviouslyIssuedEvidence(
            RunAttempt currentAttempt,
            List<EvidenceRef> evidence) {
        Set<EvidenceRef> previouslyIssued = currentAttempt.issuedEvidence().values().stream()
                .map(issuedEvidence -> issuedEvidence.evidence())
                .collect(Collectors.toSet());
        for (EvidenceRef value : evidence) {
            if (previouslyIssued.contains(value)) {
                throw protocolFailure("capability result repeats previously issued evidence");
            }
        }
    }

    private <K, V> Map<K, V> appendDistinct(
            Map<K, V> existing,
            Map<K, V> additions,
            String description) {
        Map<K, V> combined = new LinkedHashMap<>(existing);
        for (Map.Entry<K, V> entry : additions.entrySet()) {
            if (Objects.nonNull(combined.putIfAbsent(entry.getKey(), entry.getValue()))) {
                throw protocolFailure("duplicate " + description + " was issued");
            }
        }
        return Collections.unmodifiableMap(combined);
    }

    private HandleBinding binding(
            AnalysisRunId runId,
            AnalysisAttemptId attemptId,
            RevisionVector revisions) {
        return new HandleBinding(
                Objects.requireNonNull(runId, "analysis run ID must not be null"),
                Objects.requireNonNull(attemptId, "analysis attempt ID must not be null"),
                Objects.requireNonNull(revisions, "revision vector must not be null"));
    }

    private String prefix(HandleBinding binding) {
        return binding.attemptId().value() + ":";
    }

    private CapabilityExecutionContractException protocolFailure(String message) {
        return new CapabilityExecutionContractException(message);
    }

    /**
     * 一次 capability response 配發後的完整 context 與待追加 observations
     */
    public record CapabilityIssue(RunAttempt context, List<AgentObservation> observations) {
        public CapabilityIssue {
            Objects.requireNonNull(context, "issued capability context must not be null");
            Objects.requireNonNull(observations, "issued capability observations must not be null");
            observations = List.copyOf(observations);
        }
    }

    private record CapabilityIdentity(String name, String version) {
    }
}
