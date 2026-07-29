package com.java.system.agent.runtime.application.validation;

import com.java.system.agent.runtime.domain.candidate.CandidateKind;
import com.java.system.agent.runtime.domain.candidate.IssuedCandidate;
import com.java.system.agent.runtime.domain.candidate.RouteCandidate;
import com.java.system.agent.runtime.domain.evidence.ArtifactRef;
import com.java.system.agent.runtime.domain.evidence.EvidenceRef;
import com.java.system.agent.runtime.domain.evidence.IssuedEvidence;
import com.java.system.agent.runtime.domain.evidence.SemanticTarget;
import com.java.system.agent.runtime.domain.evidence.SemanticTargetKind;
import com.java.system.agent.runtime.domain.handle.CandidateHandle;
import com.java.system.agent.runtime.domain.handle.EvidenceHandle;
import com.java.system.agent.runtime.domain.handle.HandleBinding;
import com.java.system.agent.runtime.domain.observation.AgentObservation;
import com.java.system.agent.runtime.domain.observation.ObservationCode;
import com.java.system.agent.runtime.domain.observation.ObservationId;
import com.java.system.agent.runtime.domain.observation.ObservationSource;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AgentValidationContextTest {

    @Test
    void should_reject_snapshot_entries_whose_keys_do_not_match_the_issued_value() {
        HandleBinding binding = binding();
        CandidateHandle candidate = new CandidateHandle("candidate", binding, CandidateKind.ROUTE);
        CandidateHandle otherCandidate = new CandidateHandle("other-candidate", binding, CandidateKind.ROUTE);
        EvidenceHandle evidence = new EvidenceHandle("evidence", binding);
        EvidenceHandle otherEvidence = new EvidenceHandle("other-evidence", binding);
        ObservationId observation = new ObservationId("observation");
        ObservationId otherObservation = new ObservationId("other-observation");

        assertThatIllegalArgumentException().isThrownBy(() -> context(
                Map.of(otherCandidate, issuedCandidate(candidate)), Map.of(), Map.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> context(
                Map.of(), Map.of(otherEvidence, issuedEvidence(evidence)), Map.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> context(
                Map.of(), Map.of(), Map.of(otherObservation, observation(observation))));
    }

    private static AgentValidationContext context(Map<CandidateHandle, IssuedCandidate> candidates,
            Map<EvidenceHandle, IssuedEvidence> evidence, Map<ObservationId, AgentObservation> observations) {
        return new AgentValidationContext(Map.of(), candidates, evidence, observations, binding(),
                new AttemptBudget(1, 0, 1, 0, 1, 0, 1, 0));
    }

    private static HandleBinding binding() {
        RepositoryId repositoryId = new RepositoryId("repo-1");
        RevisionVector vector = RevisionVector.empty().pin(repositoryId, new RepositoryRevision("rev-1"));
        return new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"), vector);
    }

    private static IssuedCandidate issuedCandidate(CandidateHandle handle) {
        return new IssuedCandidate(handle, new RouteCandidate(new RepositoryId("repo-1"), new RepositoryRevision("rev-1"),
                "/orders", "Orders route"));
    }

    private static IssuedEvidence issuedEvidence(EvidenceHandle handle) {
        return new IssuedEvidence(handle, new EvidenceRef("semantic", new RepositoryId("repo-1"),
                new RepositoryRevision("rev-1"), new SemanticTarget(SemanticTargetKind.SYMBOL, "Orders#create", Optional.empty()),
                "Evidence", List.of(), new ArtifactRef("digest")));
    }

    private static AgentObservation observation(ObservationId id) {
        return new AgentObservation(id, ObservationSource.RUNTIME, ObservationCode.ACTION_REJECTED, "No result", Set.of(), Set.of(),
                "test");
    }
}
