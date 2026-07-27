package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.answer.AnswerStatement;
import com.java.system.agent.runtime.domain.answer.StatementId;
import com.java.system.agent.runtime.domain.answer.StatementType;
import com.java.system.agent.runtime.domain.conversation.SessionHistory;
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
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void rejects_values_tampered_behind_an_existing_handle_or_observation_id() {
        IssuedEvidence cited = evidence("evidence-1", "cited");
        AgentObservation referenced = observation("observation-1", "referenced");
        IssuedEvidence tamperedEvidence = evidence("evidence-1", "tampered");
        AgentObservation tamperedObservation = observation("observation-1", "tampered");

        assertThatThrownBy(() -> new AnswerVerificationContext(
                "question", SessionHistory.empty(), document(cited, referenced),
                List.of(cited), List.of(referenced), List.of(tamperedEvidence), List.of(referenced)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnswerVerificationContext(
                "question", SessionHistory.empty(), document(cited, referenced),
                List.of(cited), List.of(referenced), List.of(cited), List.of(tamperedObservation)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AnswerDocument document(IssuedEvidence evidence, AgentObservation observation) {
        return new AnswerDocument(List.of(new AnswerStatement(
                new StatementId("statement-1"), StatementType.QUESTION, "answer", Optional.empty(),
                Set.of(evidence.handle()), Set.of(observation.id()))));
    }

    private IssuedEvidence evidence(String handleValue, String content) {
        RepositoryId repositoryId = new RepositoryId("repo-1");
        RepositoryRevision revision = new RepositoryRevision("revision-1");
        HandleBinding binding = new HandleBinding(
                new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"),
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
