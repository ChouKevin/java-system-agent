package com.java.system.agent.answering.application.validation;

import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.ClaimId;
import com.java.system.agent.answering.domain.answer.StatementId;
import com.java.system.agent.answering.domain.answer.StatementType;
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
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QuestionPlan resolution authority 驗證測試
 */
class NeedResolutionValidatorTest {

    private final NeedResolutionValidator validator = new NeedResolutionValidator();

    @Test
    void accepts_ordered_supported_and_unavailable_resolutions_from_the_current_context() {
        HandleBinding binding = binding("attempt-1");
        EvidenceHandle evidence = new EvidenceHandle("evidence-1", binding);
        ObservationId observationId = new ObservationId("observation-1");
        QuestionPlan plan = plan("N1", "N2");
        List<NeedResolution> resolutions = List.of(
                supported("N1", evidence), unavailable("N2", observationId));
        AnswerDocument document = document(new EvidenceHandleRef(evidence.value()));

        assertThatCode(() -> validator.validate(plan, resolutions, document,
                Map.of(evidence, issued(evidence)), Map.of(observationId, observation(observationId)), binding))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("invalidResolutionCases")
    void rejects_resolution_contract_violations_with_stable_codes(
            List<NeedResolution> resolutions,
            AnswerDocument document,
            Map<EvidenceHandle, IssuedEvidence> issuedEvidence,
            Map<ObservationId, AgentObservation> observations,
            ActionRejectionCode expectedCode) {
        HandleBinding binding = binding("attempt-1");

        assertThatThrownBy(() -> validator.validate(plan("N1", "N2"), resolutions, document,
                issuedEvidence, observations, binding))
                .isInstanceOfSatisfying(NeedResolutionContractException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.rejectionCode()).isEqualTo(expectedCode));
    }

    private static Stream<Arguments> invalidResolutionCases() {
        HandleBinding current = binding("attempt-1");
        EvidenceHandle currentEvidence = new EvidenceHandle("evidence-1", current);
        EvidenceHandle citedEvidence = new EvidenceHandle("evidence-2", current);
        EvidenceHandle foreignEvidence = new EvidenceHandle("foreign-evidence", binding("attempt-2"));
        return Stream.of(
                Arguments.of(List.of(supported("N1", currentEvidence), unavailable("N3", new ObservationId("observation-1"))),
                        document(new EvidenceHandleRef(currentEvidence.value())), Map.of(currentEvidence, issued(currentEvidence)), Map.of(),
                        ActionRejectionCode.QUESTION_PLAN_RESOLUTION_MISMATCH),
                Arguments.of(List.of(supported("N1", foreignEvidence), supported("N2", currentEvidence)),
                        document(new EvidenceHandleRef(foreignEvidence.value())),
                        Map.of(foreignEvidence, issued(foreignEvidence), currentEvidence, issued(currentEvidence)), Map.of(),
                        ActionRejectionCode.UNKNOWN_RESOLUTION_EVIDENCE),
                Arguments.of(List.of(supported("N1", currentEvidence), supported("N2", citedEvidence)),
                        document(new EvidenceHandleRef(citedEvidence.value())),
                        Map.of(currentEvidence, issued(currentEvidence), citedEvidence, issued(citedEvidence)), Map.of(),
                        ActionRejectionCode.UNCITED_RESOLUTION_EVIDENCE),
                Arguments.of(List.of(supported("N1", currentEvidence), unavailable("N2", new ObservationId("unknown-observation"))),
                        document(new EvidenceHandleRef(currentEvidence.value())), Map.of(currentEvidence, issued(currentEvidence)), Map.of(),
                        ActionRejectionCode.UNKNOWN_RESOLUTION_OBSERVATION));
    }

    private static QuestionPlan plan(String first, String second) {
        return new QuestionPlan(List.of(
                new InformationNeed(new InformationNeedId(first), "Need " + first),
                new InformationNeed(new InformationNeedId(second), "Need " + second)));
    }

    private static NeedResolution supported(String id, EvidenceHandle evidence) {
        return new NeedResolution(new InformationNeedId(id), NeedResolutionStatus.SUPPORTED,
                Set.of(new EvidenceHandleRef(evidence.value())), Set.of());
    }

    private static NeedResolution unavailable(String id, ObservationId observationId) {
        return new NeedResolution(new InformationNeedId(id), NeedResolutionStatus.UNAVAILABLE,
                Set.of(), Set.of(observationId));
    }

    private static AnswerDocument document(EvidenceHandleRef evidence) {
        return new AnswerDocument(List.of(new AnswerStatement(new StatementId("statement-1"), StatementType.FACT,
                "The supported need is evidenced", Optional.of(new ClaimId("claim-1")), Set.of(evidence), Set.of())));
    }

    private static HandleBinding binding(String attempt) {
        RepositoryId repository = new RepositoryId("repo-1");
        return new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId(attempt),
                RevisionVector.empty().pin(repository, new RepositoryRevision("rev-1")));
    }

    private static IssuedEvidence issued(EvidenceHandle handle) {
        return new IssuedEvidence(handle, new EvidenceRef("semantic", new RepositoryId("repo-1"),
                new RepositoryRevision("rev-1"), new SemanticTarget(SemanticTargetKind.SYMBOL, "Orders#create", Optional.empty()),
                "Evidence", List.of(), new ArtifactRef("digest")));
    }

    private static AgentObservation observation(ObservationId id) {
        return new AgentObservation(id, ObservationSource.RUNTIME, ObservationCode.UNADDRESSED_PART,
                "The need cannot be resolved", Set.of(), Set.of(), "runtime");
    }
}
