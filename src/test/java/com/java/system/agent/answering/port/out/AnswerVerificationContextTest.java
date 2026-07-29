package com.java.system.agent.answering.port.out;

import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.StatementId;
import com.java.system.agent.answering.domain.answer.StatementType;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.evidence.ArtifactRef;
import com.java.system.agent.answering.domain.evidence.EvidenceRef;
import com.java.system.agent.answering.domain.evidence.IssuedEvidence;
import com.java.system.agent.answering.domain.evidence.SemanticTarget;
import com.java.system.agent.answering.domain.evidence.SemanticTargetKind;
import com.java.system.agent.answering.domain.handle.EvidenceHandle;
import com.java.system.agent.answering.domain.handle.EvidenceHandleRef;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.observation.AgentObservation;
import com.java.system.agent.answering.domain.observation.ObservationCode;
import com.java.system.agent.answering.domain.observation.ObservationId;
import com.java.system.agent.answering.domain.observation.ObservationSource;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerVerificationContextTest {

    @Test
    void retains_the_ordered_complete_subset_of_available_values() {
        IssuedEvidence cited = evidence("evidence-1", "cited");
        IssuedEvidence otherEvidence = evidence("evidence-2", "other");
        AgentObservation referenced = observation("observation-1", "referenced");
        AgentObservation otherObservation = observation("observation-2", "other");

        AnswerVerificationContext context = new AnswerVerificationContext(
                "question", SessionHistory.empty(), document(cited, referenced),
                List.of(cited, otherEvidence), List.of(referenced, otherObservation),
                List.of(cited), List.of(referenced));

        assertThat(context.availableEvidence()).containsExactly(cited, otherEvidence);
        assertThat(context.availableObservations()).containsExactly(referenced, otherObservation);
        assertThat(context.citedEvidence()).containsExactly(cited);
        assertThat(context.referencedObservations()).containsExactly(referenced);
    }

    @Test
    void compares_reissued_evidence_and_observations_by_opaque_reference_values() {
        IssuedEvidence cited = evidence("evidence-1", "cited");
        AgentObservation referenced = observation("observation-1", "referenced");
        IssuedEvidence tamperedEvidence = evidence("evidence-1", "tampered");
        AgentObservation tamperedObservation = observation("observation-1", "tampered");

        AnswerVerificationContext context = new AnswerVerificationContext(
                "question", SessionHistory.empty(), document(cited, referenced),
                List.of(cited), List.of(referenced), List.of(tamperedEvidence), List.of(referenced));
        assertThat(context.citedEvidence()).containsExactly(tamperedEvidence);
        AnswerVerificationContext reissuedObservationContext = new AnswerVerificationContext(
                "question", SessionHistory.empty(), document(cited, referenced),
                List.of(cited), List.of(referenced), List.of(cited), List.of(tamperedObservation));
        assertThat(reissuedObservationContext.referencedObservations()).containsExactly(tamperedObservation);
    }

    @Test
    void accepts_exact_citation_values_when_evidence_is_reissued_with_a_new_binding() {
        IssuedEvidence cited = evidence("evidence-1", "cited");
        IssuedEvidence reissued = evidence("evidence-1", "reissued", "attempt-2");
        AgentObservation referenced = observation("observation-1", "referenced");

        AnswerVerificationContext context = new AnswerVerificationContext(
                "question", SessionHistory.empty(), document(new EvidenceHandleRef("evidence-1"), referenced),
                List.of(reissued), List.of(referenced), List.of(reissued), List.of(referenced));

        assertThat(context.citedEvidence()).containsExactly(reissued);
    }

    private AnswerDocument document(IssuedEvidence evidence, AgentObservation observation) {
        return document(new EvidenceHandleRef(evidence.handle().value()), observation);
    }

    private AnswerDocument document(EvidenceHandleRef evidence, AgentObservation observation) {
        return new AnswerDocument(List.of(new AnswerStatement(
                new StatementId("statement-1"), StatementType.QUESTION, "answer", Optional.empty(),
                Set.of(evidence), Set.of(observation.id()))));
    }

    private IssuedEvidence evidence(String handleValue, String content) {
        return evidence(handleValue, content, "attempt-1");
    }

    private IssuedEvidence evidence(String handleValue, String content, String attemptId) {
        RepositoryId repositoryId = new RepositoryId("repo-1");
        RepositoryRevision revision = new RepositoryRevision("revision-1");
        HandleBinding binding = new HandleBinding(
                new AnalysisRunId("run-1"), new AnalysisAttemptId(attemptId),
                RevisionVector.empty().pin(repositoryId, revision));
        return new IssuedEvidence(new EvidenceHandle(handleValue, binding), new EvidenceRef(
                "semantic", repositoryId, revision,
                new SemanticTarget(SemanticTargetKind.SYMBOL, "Orders#create", Optional.empty()),
                content, List.of(), new ArtifactRef("artifact-" + content)));
    }

    private AgentObservation observation(String id, String description) {
        return new AgentObservation(new ObservationId(id), ObservationSource.RUNTIME,
                ObservationCode.UNRESOLVED_CALL, description, Set.of(), Set.of(), "test");
    }
}
