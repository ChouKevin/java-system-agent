package com.java.system.agent.answering.port.out;

import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.StatementId;
import com.java.system.agent.answering.domain.answer.StatementType;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
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
import com.java.system.agent.answering.domain.plan.InformationNeed;
import com.java.system.agent.answering.domain.plan.InformationNeedId;
import com.java.system.agent.answering.domain.plan.NeedResolution;
import com.java.system.agent.answering.domain.plan.NeedResolutionStatus;
import com.java.system.agent.answering.domain.plan.QuestionPlan;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.EvidenceCapabilityProvenance;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
        QuestionPlan plan = questionPlan();
        List<NeedResolution> resolutions = new ArrayList<>(List.of(new NeedResolution(
                new InformationNeedId("need-1"), NeedResolutionStatus.UNAVAILABLE, Set.of(),
                Set.of(referenced.id()))));

        AnswerVerificationContext context = new AnswerVerificationContext(
                "question", SessionHistory.empty(), document(cited, referenced),
                List.of(cited, otherEvidence), List.of(referenced, otherObservation),
                List.of(cited), List.of(referenced), List.of(), plan, resolutions);
        resolutions.add(new NeedResolution(new InformationNeedId("need-2"), NeedResolutionStatus.UNAVAILABLE,
                Set.of(), Set.of(otherObservation.id())));

        assertThat(context.availableEvidence()).containsExactly(cited, otherEvidence);
        assertThat(context.availableObservations()).containsExactly(referenced, otherObservation);
        assertThat(context.citedEvidence()).containsExactly(cited);
        assertThat(context.referencedObservations()).containsExactly(referenced);
        assertThat(context.questionPlan()).isEqualTo(plan);
        assertThat(context.needResolutions()).containsExactly(new NeedResolution(
                new InformationNeedId("need-1"), NeedResolutionStatus.UNAVAILABLE, Set.of(),
                Set.of(referenced.id())));
        assertThatThrownBy(() -> context.needResolutions().add(new NeedResolution(
                new InformationNeedId("need-2"), NeedResolutionStatus.UNAVAILABLE, Set.of(),
                Set.of(otherObservation.id())))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void compares_reissued_evidence_and_observations_by_opaque_reference_values() {
        IssuedEvidence cited = evidence("evidence-1", "cited");
        AgentObservation referenced = observation("observation-1", "referenced");
        IssuedEvidence tamperedEvidence = evidence("evidence-1", "tampered");
        AgentObservation tamperedObservation = observation("observation-1", "tampered");

        AnswerVerificationContext context = new AnswerVerificationContext(
                "question", SessionHistory.empty(), document(cited, referenced),
                List.of(cited), List.of(referenced), List.of(tamperedEvidence), List.of(referenced),
                List.of(), questionPlan(), List.of());
        assertThat(context.citedEvidence()).containsExactly(tamperedEvidence);
        AnswerVerificationContext reissuedObservationContext = new AnswerVerificationContext(
                "question", SessionHistory.empty(), document(cited, referenced),
                List.of(cited), List.of(referenced), List.of(cited), List.of(tamperedObservation),
                List.of(), questionPlan(), List.of());
        assertThat(reissuedObservationContext.referencedObservations()).containsExactly(tamperedObservation);
    }

    @Test
    void accepts_exact_citation_values_when_evidence_is_reissued_with_a_new_binding() {
        IssuedEvidence cited = evidence("evidence-1", "cited");
        IssuedEvidence reissued = evidence("evidence-1", "reissued", "attempt-2");
        AgentObservation referenced = observation("observation-1", "referenced");

        AnswerVerificationContext context = new AnswerVerificationContext(
                "question", SessionHistory.empty(), document(new EvidenceHandleRef("evidence-1"), referenced),
                List.of(reissued), List.of(referenced), List.of(reissued), List.of(referenced),
                List.of(), questionPlan(), List.of());

        assertThat(context.citedEvidence()).containsExactly(reissued);
    }

    @Test
    void rejects_capability_provenance_for_evidence_outside_the_available_context() {
        IssuedEvidence cited = evidence("evidence-1", "cited");
        IssuedEvidence foreign = evidence("evidence-2", "foreign");
        AgentObservation referenced = observation("observation-1", "referenced");
        CapabilityPolicy capability = new CapabilityPolicy("codebase_find_internal_references", "v1");

        assertThatThrownBy(() -> new AnswerVerificationContext(
                "question", SessionHistory.empty(), document(cited, referenced),
                List.of(cited), List.of(referenced), List.of(cited), List.of(referenced),
                List.of(new EvidenceCapabilityProvenance(foreign.handle(), capability)), questionPlan(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("available evidence");
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

    private QuestionPlan questionPlan() {
        return new QuestionPlan(List.of(new InformationNeed(new InformationNeedId("need-1"), "Resolve the request")));
    }
}
