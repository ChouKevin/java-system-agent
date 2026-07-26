package com.java.system.agent.runtime.application.validation;

import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.answer.AnswerStatement;
import com.java.system.agent.runtime.domain.answer.ClaimId;
import com.java.system.agent.runtime.domain.answer.StatementId;
import com.java.system.agent.runtime.domain.answer.StatementType;
import com.java.system.agent.runtime.domain.evidence.ArtifactRef;
import com.java.system.agent.runtime.domain.evidence.EvidenceRef;
import com.java.system.agent.runtime.domain.evidence.IssuedEvidence;
import com.java.system.agent.runtime.domain.evidence.SemanticTarget;
import com.java.system.agent.runtime.domain.evidence.SemanticTargetKind;
import com.java.system.agent.runtime.domain.handle.EvidenceHandle;
import com.java.system.agent.runtime.domain.handle.HandleBinding;
import com.java.system.agent.runtime.domain.observation.AgentObservation;
import com.java.system.agent.runtime.domain.observation.ObservationCode;
import com.java.system.agent.runtime.domain.observation.ObservationId;
import com.java.system.agent.runtime.domain.observation.ObservationSource;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AnswerDocumentValidatorTest {

    private final AnswerDocumentValidator validator = new AnswerDocumentValidator();

    @Test
    void should_reject_unknown_evidence_citation() {
        Fixture fixture = fixture();
        EvidenceHandle unknown = new EvidenceHandle("unknown", fixture.binding());

        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(
                document(fact(unknown)), fixture.evidence(), fixture.observations(), fixture.binding()));
    }

    @Test
    void should_reject_evidence_from_a_stale_or_different_revision() {
        Fixture fixture = fixture();
        HandleBinding staleBinding = binding("attempt-1", "rev-2");
        EvidenceHandle stale = new EvidenceHandle("stale", staleBinding);
        IssuedEvidence issued = issued(stale, "rev-2");

        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(
                document(fact(stale)), Map.of(stale, issued), fixture.observations(), fixture.binding()));
    }

    @Test
    void should_reject_unknown_observation_and_limitation_without_linkage() {
        Fixture fixture = fixture();
        ObservationId unknown = new ObservationId("unknown");
        AnswerStatement unknownObservation = new AnswerStatement(
                new StatementId("uncertain"), StatementType.UNCERTAINTY, "Uncertain", Optional.empty(), Set.of(), Set.of(unknown));
        AnswerStatement unlinkedLimitation = new AnswerStatement(
                new StatementId("limitation"), StatementType.LIMITATION, "Limited", Optional.empty(), Set.of(), Set.of());

        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(
                document(unknownObservation), fixture.evidence(), fixture.observations(), fixture.binding()));
        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(
                document(unlinkedLimitation), fixture.evidence(), fixture.observations(), fixture.binding()));
    }

    @Test
    void should_return_only_cited_evidence_and_referenced_observations() {
        Fixture fixture = fixture();
        AnswerStatement limitation = new AnswerStatement(
                new StatementId("limitation"), StatementType.LIMITATION, "Limited", Optional.empty(), Set.of(), Set.of(fixture.observationId()));

        AnswerDocumentValidation result = validator.validate(
                new AnswerDocument(List.of(fact(fixture.evidenceHandle()), limitation)),
                fixture.evidence(), fixture.observations(), fixture.binding());

        assertThat(result.citedEvidence()).containsOnlyKeys(fixture.evidenceHandle());
        assertThat(result.referencedObservations()).containsOnlyKeys(fixture.observationId());
    }

    private static AnswerDocument document(AnswerStatement statement) {
        return new AnswerDocument(List.of(statement));
    }

    private static AnswerStatement fact(EvidenceHandle evidence) {
        return new AnswerStatement(new StatementId("fact"), StatementType.FACT, "Orders are created", Optional.of(new ClaimId("claim")), Set.of(evidence), Set.of());
    }

    private static Fixture fixture() {
        HandleBinding binding = binding("attempt-1", "rev-1");
        EvidenceHandle evidenceHandle = new EvidenceHandle("evidence", binding);
        ObservationId observationId = new ObservationId("observation");
        return new Fixture(binding, evidenceHandle, observationId,
                Map.of(evidenceHandle, issued(evidenceHandle, "rev-1")),
                Map.of(observationId, new AgentObservation(observationId, ObservationSource.RUNTIME,
                        ObservationCode.UNRESOLVED_CALL, "Cannot resolve", Set.of(), Set.of(evidenceHandle), "test")));
    }

    private static HandleBinding binding(String attempt, String revision) {
        RepositoryId repositoryId = new RepositoryId("repo-1");
        RevisionVector vector = RevisionVector.empty().pin(repositoryId, new RepositoryRevision(revision));
        return new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId(attempt), vector);
    }

    private static IssuedEvidence issued(EvidenceHandle handle, String revision) {
        return new IssuedEvidence(handle, new EvidenceRef("semantic", new RepositoryId("repo-1"),
                new RepositoryRevision(revision), new SemanticTarget(SemanticTargetKind.SYMBOL, "Orders#create", Optional.empty()),
                "Evidence", List.of(), new ArtifactRef("digest")));
    }

    private record Fixture(HandleBinding binding, EvidenceHandle evidenceHandle, ObservationId observationId,
                           Map<EvidenceHandle, IssuedEvidence> evidence,
                           Map<ObservationId, AgentObservation> observations) {
    }
}
