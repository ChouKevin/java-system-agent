package com.java.system.agent.answering.application.loop;

import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.candidate.FollowUpCandidate;
import com.java.system.agent.answering.domain.candidate.RepositoryCandidate;
import com.java.system.agent.answering.domain.evidence.ArtifactRef;
import com.java.system.agent.answering.domain.evidence.EvidenceRef;
import com.java.system.agent.answering.domain.evidence.SemanticTarget;
import com.java.system.agent.answering.domain.evidence.SemanticTargetKind;
import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.observation.ObservationCode;
import com.java.system.agent.answering.domain.observation.CapabilityObservation;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.RunAttempt;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.answering.port.out.CapabilityExecutionContractException;
import com.java.system.agent.answering.port.out.RepositoryDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ContextIssuer opaque handle 與完整 rebind 邊界測試
 */
class ContextIssuerTest {

    private final ContextIssuer issuer = new ContextIssuer();
    private final AnalysisRunId runId = new AnalysisRunId("run-1");
    private final AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
    private final RepositoryId repositoryId = new RepositoryId("repo-1");

    @Test
    void issuesDeterministicCatalogHandlesAndRebindsWithoutRetainingOldHandles() {
        RunAttempt initial = issuer.issueInitial(
                runId,
                attemptId,
                RevisionVector.empty(),
                List.of(capability("zeta"), capability("alpha")),
                List.of(new RepositoryDescriptor(repositoryId, "Repository one")));

        assertThat(initial.issuedCapabilities().keySet())
                .extracting(handle -> handle.value())
                .containsExactly("attempt-1:C1", "attempt-1:C2");
        assertThat(initial.issuedCapabilities().values())
                .extracting(capabilityPolicy -> capabilityPolicy.name())
                .containsExactly("alpha", "zeta");
        assertThat(initial.issuedCandidates().keySet())
                .extracting(handle -> handle.value())
                .containsExactly("attempt-1:R1");

        RevisionVector pinned = pin(repositoryId, new RepositoryRevision("rev-1"));
        RunAttempt rebound = issuer.reissue(runId, initial, pinned);

        assertThat(rebound.issuedCandidates().keySet().iterator().next().binding().revisionVector())
                .isEqualTo(pinned);
        assertThat(rebound.issuedCandidates())
                .doesNotContainKey(initial.issuedCandidates().keySet().iterator().next());
    }

    @Test
    void rejectsObservationReferencesThatWereNotReturnedInTheSameSemanticResult() {
        RevisionVector pinned = pin(repositoryId, new RepositoryRevision("rev-1"));
        RunAttempt initial = issuer.issueInitial(
                runId,
                attemptId,
                pinned,
                List.of(capability("find")),
                List.of(new RepositoryDescriptor(repositoryId, "Repository one")));
        RepositoryCandidate foreign = new RepositoryCandidate(repositoryId, "Not returned");
        CapabilityObservation observation = new CapabilityObservation(
                ObservationCode.AMBIGUOUS_REPOSITORY,
                "Candidate is ambiguous",
                List.of(foreign),
                List.of(),
                "semantic-service");

        assertThatThrownBy(() -> issuer.issueCapabilityResult(
                runId,
                initial,
                new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of(observation)),
                Set.of(repositoryId)))
                .isInstanceOf(CapabilityExecutionContractException.class)
                .hasMessageContaining("outside the same result");
    }

    @Test
    void rejectsSemanticValuesForRepositoriesOutsideTheTrustedCatalog() {
        RevisionVector pinned = pin(repositoryId, new RepositoryRevision("rev-1"));
        RunAttempt initial = issuer.issueInitial(
                runId,
                attemptId,
                pinned,
                List.of(capability("find")),
                List.of(new RepositoryDescriptor(repositoryId, "Repository one")));
        RepositoryId unknownRepository = new RepositoryId("repo-unknown");

        assertThatThrownBy(() -> issuer.issueCapabilityResult(
                runId,
                initial,
                new CapabilityExecutionResult.Succeeded(
                        List.of(new RepositoryCandidate(unknownRepository, "Unknown repository")),
                        List.of(),
                        List.of()),
                Set.of(repositoryId)))
                .isInstanceOf(CapabilityExecutionContractException.class)
                .hasMessageContaining("candidate repository is absent from the catalog");
        assertThatThrownBy(() -> issuer.issueCapabilityResult(
                runId,
                initial,
                new CapabilityExecutionResult.Succeeded(
                        List.of(),
                        List.of(evidence(unknownRepository, "rev-1", "unknown")),
                        List.of()),
                Set.of(repositoryId)))
                .isInstanceOf(CapabilityExecutionContractException.class)
                .hasMessageContaining("evidence repository is absent from the catalog");
    }

    @Test
    void rejectsDuplicateCatalogAndSemanticValuesInsteadOfMintingAmbiguousHandles() {
        RepositoryDescriptor repository = new RepositoryDescriptor(repositoryId, "Repository one");
        assertThatThrownBy(() -> issuer.issueInitial(
                runId,
                attemptId,
                RevisionVector.empty(),
                List.of(capability("find")),
                List.of(repository, repository)))
                .isInstanceOf(CapabilityExecutionContractException.class)
                .hasMessageContaining("duplicate repository ID");

        RevisionVector pinned = pin(repositoryId, new RepositoryRevision("rev-1"));
        RunAttempt initial = issuer.issueInitial(
                runId,
                attemptId,
                pinned,
                List.of(capability("find")),
                List.of(repository));
        RepositoryCandidate candidate = new RepositoryCandidate(repositoryId, "Discovered repository");
        EvidenceRef evidence = evidence(repositoryId, "rev-1", "duplicate");

        assertThatThrownBy(() -> issuer.issueCapabilityResult(
                runId,
                initial,
                new CapabilityExecutionResult.Succeeded(List.of(candidate, candidate), List.of(), List.of()),
                Set.of(repositoryId)))
                .isInstanceOf(CapabilityExecutionContractException.class)
                .hasMessageContaining("duplicate candidate");
        assertThatThrownBy(() -> issuer.issueCapabilityResult(
                runId,
                initial,
                new CapabilityExecutionResult.Succeeded(List.of(), List.of(evidence, evidence), List.of()),
                Set.of(repositoryId)))
                .isInstanceOf(CapabilityExecutionContractException.class)
                .hasMessageContaining("duplicate evidence");
    }

    @Test
    void issues_follow_up_handles_and_rebinds_without_changing_the_payload() {
        RepositoryRevision revision = new RepositoryRevision("rev-1");
        RevisionVector pinned = pin(repositoryId, revision);
        RunAttempt initial = issuer.issueInitial(
                runId,
                attemptId,
                pinned,
                List.of(capability("find")),
                List.of(new RepositoryDescriptor(repositoryId, "Repository one")));
        FollowUpCandidate followUp = new FollowUpCandidate(
                repositoryId,
                revision,
                "codebase_get_source_segment",
                "v1",
                new CapabilityInputPayload("{\"contextLines\":0,\"location\":{\"sourceFile\":\"Example.java\"}}"),
                "Read the next bounded source segment");

        ContextIssuer.CapabilityIssue issue = issuer.issueCapabilityResult(
                runId,
                initial,
                new CapabilityExecutionResult.Succeeded(List.of(followUp), List.of(), List.of()),
                Set.of(repositoryId));
        CandidateHandle followUpHandle = issue.context().issuedCandidates().keySet().stream()
                .filter(handle -> handle.kind() == CandidateKind.FOLLOW_UP)
                .findFirst()
                .orElseThrow();
        RepositoryId additionalRepository = new RepositoryId("repo-2");
        RevisionVector reissuedRevisions = pinned.pin(additionalRepository, new RepositoryRevision("rev-2"));

        RunAttempt rebound = issuer.reissue(runId, issue.context(), reissuedRevisions);
        CandidateHandle reboundHandle = rebound.issuedCandidates().keySet().stream()
                .filter(handle -> handle.kind() == CandidateKind.FOLLOW_UP)
                .findFirst()
                .orElseThrow();

        assertThat(followUpHandle.binding().revisionVector()).isEqualTo(pinned);
        assertThat(reboundHandle.binding().revisionVector()).isEqualTo(reissuedRevisions);
        assertThat(reboundHandle).isNotEqualTo(followUpHandle);
        assertThat(rebound.issuedCandidates().get(reboundHandle).candidate())
                .isEqualTo(followUp);
        assertThat(((FollowUpCandidate) rebound.issuedCandidates().get(reboundHandle).candidate()).payload())
                .isEqualTo(followUp.payload());
    }

    private CapabilityPolicy capability(String name) {
        return new CapabilityPolicy(
                name,
                "v1",
                Set.of(CandidateKind.REPOSITORY),
                1,
                10);
    }

    private RevisionVector pin(RepositoryId id, RepositoryRevision revision) {
        return RevisionVector.empty().pin(id, revision);
    }

    private EvidenceRef evidence(RepositoryId id, String revision, String digest) {
        return new EvidenceRef(
                "semantic",
                id,
                new RepositoryRevision(revision),
                new SemanticTarget(SemanticTargetKind.SYMBOL, "Orders#create", Optional.empty()),
                "Evidence",
                List.of(),
                new ArtifactRef(digest));
    }
}
