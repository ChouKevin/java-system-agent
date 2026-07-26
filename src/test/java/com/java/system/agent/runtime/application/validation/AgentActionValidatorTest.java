package com.java.system.agent.runtime.application.validation;

import com.java.system.agent.runtime.domain.action.AgentAction;
import com.java.system.agent.runtime.domain.action.AnswerAction;
import com.java.system.agent.runtime.domain.action.ClarifyAction;
import com.java.system.agent.runtime.domain.action.QueryAction;
import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.answer.AnswerStatement;
import com.java.system.agent.runtime.domain.answer.ClaimId;
import com.java.system.agent.runtime.domain.answer.StatementId;
import com.java.system.agent.runtime.domain.answer.StatementType;
import com.java.system.agent.runtime.domain.capability.ArgumentDefinition;
import com.java.system.agent.runtime.domain.capability.ArgumentType;
import com.java.system.agent.runtime.domain.capability.CapabilityDescriptor;
import com.java.system.agent.runtime.domain.capability.CapabilityQuerySchema;
import com.java.system.agent.runtime.domain.candidate.CandidateKind;
import com.java.system.agent.runtime.domain.candidate.IssuedCandidate;
import com.java.system.agent.runtime.domain.candidate.RepositoryCandidate;
import com.java.system.agent.runtime.domain.candidate.RouteCandidate;
import com.java.system.agent.runtime.domain.evidence.ArtifactRef;
import com.java.system.agent.runtime.domain.evidence.EvidenceRef;
import com.java.system.agent.runtime.domain.evidence.IssuedEvidence;
import com.java.system.agent.runtime.domain.evidence.SemanticTarget;
import com.java.system.agent.runtime.domain.evidence.SemanticTargetKind;
import com.java.system.agent.runtime.domain.handle.CapabilityHandle;
import com.java.system.agent.runtime.domain.handle.CandidateHandle;
import com.java.system.agent.runtime.domain.handle.HandleBinding;
import com.java.system.agent.runtime.domain.handle.EvidenceHandle;
import com.java.system.agent.runtime.domain.observation.ObservationId;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentActionValidatorTest {

    private final AgentActionValidator validator = new AgentActionValidator();

    @Test
    void should_reject_unknown_candidate_without_modifying_action() {
        Fixture fixture = fixture();
        CandidateHandle unknown = new CandidateHandle("unknown", fixture.binding(), CandidateKind.ROUTE);
        QueryAction action = fixture.query(List.of(unknown), "1");

        ActionValidation result = validator.validate(action, fixture.context());

        assertRejected(result, ActionRejectionCode.UNKNOWN_CANDIDATE, action);
    }

    @Test
    void should_reject_cross_attempt_handle_before_candidate_lookup() {
        Fixture fixture = fixture();
        HandleBinding otherBinding = binding("attempt-2", "rev-1");
        CandidateHandle foreign = new CandidateHandle("candidate-1", otherBinding, CandidateKind.ROUTE);
        QueryAction action = fixture.query(List.of(foreign), "1");

        assertRejected(validator.validate(action, fixture.context()), ActionRejectionCode.CROSS_ATTEMPT, action);
    }

    @Test
    void should_reject_same_attempt_candidate_from_an_older_revision_as_stale() {
        Fixture fixture = fixture();
        CandidateHandle stale = new CandidateHandle("candidate-1", binding("attempt-1", "rev-0"), CandidateKind.ROUTE);
        QueryAction action = fixture.query(List.of(stale), "1");

        assertRejected(validator.validate(action, fixture.contextWithCandidates(Map.of(
                stale, issued(stale, "rev-0")))), ActionRejectionCode.STALE_REVISION, action);
    }

    @Test
    void should_reject_same_attempt_capability_from_an_older_revision_as_stale() {
        Fixture fixture = fixture();
        CapabilityHandle stale = new CapabilityHandle("capability-1", binding("attempt-1", "rev-0"));
        QueryAction action = new QueryAction(stale, List.of(fixture.candidate()), "Find callers", Map.of("depth", "1"),
                "Need call graph");

        assertRejected(validator.validate(action, fixture.context()), ActionRejectionCode.STALE_REVISION, action);
    }

    @Test
    void should_accept_multiple_candidates_in_the_original_order() {
        Fixture fixture = fixture();
        CandidateHandle second = new CandidateHandle("candidate-2", fixture.binding(), CandidateKind.ROUTE);
        QueryAction action = fixture.query(List.of(second, fixture.candidate()), "1");

        ActionValidation result = validator.validate(action, fixture.contextWithCandidates(Map.of(
                fixture.candidate(), issued(fixture.candidate()), second, issued(second))));

        assertThat(result).isInstanceOf(ActionValidation.Accepted.class);
        assertThat(((ActionValidation.Accepted) result).originalAction()).isSameAs(action);
        assertThat(((QueryAction) ((ActionValidation.Accepted) result).originalAction()).candidates())
                .containsExactly(second, fixture.candidate());
    }

    @Test
    void should_reject_incompatible_candidate_kind() {
        Fixture fixture = fixture();
        CandidateHandle repository = new CandidateHandle("repository", fixture.binding(), CandidateKind.REPOSITORY);
        QueryAction action = fixture.query(List.of(repository), "1");

        assertRejected(validator.validate(action, fixture.contextWithCandidates(Map.of(
                repository, new IssuedCandidate(repository,
                        new RepositoryCandidate(new RepositoryId("repo-1"), "Orders repository"))))),
                ActionRejectionCode.INCOMPATIBLE_CANDIDATE_KIND, action);
    }

    @Test
    void should_reject_depth_outside_integer_range() {
        Fixture fixture = fixture();
        QueryAction action = fixture.query(List.of(fixture.candidate()), "3");

        assertRejected(validator.validate(action, fixture.context()), ActionRejectionCode.INVALID_ARGUMENTS, action);
    }

    @Test
    void should_reject_query_during_final_response_mode() {
        Fixture fixture = fixture(true);
        QueryAction action = fixture.query(List.of(fixture.candidate()), "1");

        assertRejected(validator.validate(action, fixture.context()), ActionRejectionCode.FINAL_RESPONSE_REQUIRED, action);
    }

    @Test
    void should_reject_clarify_with_cross_attempt_candidate() {
        Fixture fixture = fixture(true);
        CandidateHandle foreign = new CandidateHandle("candidate-1", binding("attempt-2", "rev-1"), CandidateKind.ROUTE);
        ClarifyAction action = new ClarifyAction("Which route?", List.of(foreign), "Need disambiguation");

        assertRejected(validator.validate(action, fixture.context()), ActionRejectionCode.CROSS_ATTEMPT, action);
    }

    @Test
    void should_reject_answer_action_with_cross_attempt_evidence() {
        Fixture fixture = fixture(true);
        EvidenceHandle foreign = new EvidenceHandle("evidence", binding("attempt-2", "rev-1"));
        AnswerAction action = new AnswerAction(document(foreign));

        assertRejected(validator.validate(action, fixture.contextWithEvidence(Map.of(
                foreign, issuedEvidence(foreign)))), ActionRejectionCode.CROSS_ATTEMPT, action);
    }

    @Test
    void should_reject_answer_action_with_same_attempt_evidence_from_an_older_revision_as_stale() {
        Fixture fixture = fixture(true);
        EvidenceHandle stale = new EvidenceHandle("evidence", binding("attempt-1", "rev-0"));
        AnswerAction action = new AnswerAction(document(stale));

        assertRejected(validator.validate(action, fixture.contextWithEvidence(Map.of(
                stale, issuedEvidence(stale, "rev-0")))), ActionRejectionCode.STALE_REVISION, action);
    }

    @Test
    void should_reject_answer_action_with_unknown_observation_or_unlinked_limitation() {
        Fixture fixture = fixture(true);
        EvidenceHandle evidence = new EvidenceHandle("evidence", fixture.binding());
        AnswerStatement unknownObservation = new AnswerStatement(new StatementId("uncertain"), StatementType.UNCERTAINTY,
                "Uncertain", Optional.empty(), Set.of(), Set.of(new ObservationId("unknown")));
        AnswerStatement limitation = new AnswerStatement(new StatementId("limitation"), StatementType.LIMITATION,
                "Limited", Optional.empty(), Set.of(), Set.of());
        AnswerAction unknownObservationAction = new AnswerAction(new AnswerDocument(List.of(unknownObservation)));
        AnswerAction limitationAction = new AnswerAction(new AnswerDocument(List.of(limitation)));

        assertRejected(validator.validate(unknownObservationAction,
                fixture.contextWithEvidence(Map.of(evidence, issuedEvidence(evidence)))),
                ActionRejectionCode.UNKNOWN_OBSERVATION, unknownObservationAction);
        assertRejected(validator.validate(limitationAction,
                fixture.contextWithEvidence(Map.of(evidence, issuedEvidence(evidence)))),
                ActionRejectionCode.INVALID_ANSWER_DOCUMENT, limitationAction);
    }

    @Test
    void should_accept_answer_action_with_issued_current_evidence() {
        Fixture fixture = fixture(true);
        EvidenceHandle evidence = new EvidenceHandle("evidence", fixture.binding());
        AnswerAction action = new AnswerAction(document(evidence));

        ActionValidation result = validator.validate(action,
                fixture.contextWithEvidence(Map.of(evidence, issuedEvidence(evidence))));

        assertThat(result).isInstanceOf(ActionValidation.Accepted.class);
        assertThat(((ActionValidation.Accepted) result).originalAction()).isSameAs(action);
    }

    private static void assertRejected(ActionValidation result, ActionRejectionCode code, AgentAction action) {
        assertThat(result).isInstanceOf(ActionValidation.Rejected.class);
        ActionValidation.Rejected rejected = (ActionValidation.Rejected) result;
        assertThat(rejected.code()).isEqualTo(code);
        assertThat(rejected.originalAction()).isSameAs(action);
    }

    private static Fixture fixture() {
        return fixture(false);
    }

    private static Fixture fixture(boolean finalResponseMode) {
        HandleBinding binding = binding("attempt-1", "rev-1");
        CandidateHandle candidate = new CandidateHandle("candidate-1", binding, CandidateKind.ROUTE);
        CapabilityHandle capability = new CapabilityHandle("capability-1", binding);
        CapabilityDescriptor descriptor = new CapabilityDescriptor(
                "callers", "v1", Set.of(CandidateKind.ROUTE), 1, 2,
                new CapabilityQuerySchema(List.of(
                        new ArgumentDefinition("depth", ArgumentType.INTEGER, true, 1, 2, Set.of()))));
        Map<CandidateHandle, IssuedCandidate> candidates = new LinkedHashMap<>();
        candidates.put(candidate, issued(candidate));
        AgentValidationContext context = new AgentValidationContext(
                Map.of(capability, descriptor), candidates, Map.of(), Map.of(), binding,
                new AttemptBudget(4, 0, 4, 0, 4, 0, 4, 0, 1, 0), finalResponseMode);
        return new Fixture(binding, capability, candidate, descriptor, finalResponseMode, context);
    }

    private static HandleBinding binding(String attempt, String revision) {
        RepositoryId repositoryId = new RepositoryId("repo-1");
        RevisionVector vector = RevisionVector.empty().pin(repositoryId, new RepositoryRevision(revision));
        return new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId(attempt), vector);
    }

    private static IssuedCandidate issued(CandidateHandle handle) {
        return issued(handle, "rev-1");
    }

    private static IssuedCandidate issued(CandidateHandle handle, String revision) {
        return new IssuedCandidate(handle, new RouteCandidate(
                new RepositoryId("repo-1"), new RepositoryRevision(revision), "/orders", "Orders route"));
    }

    private static AnswerDocument document(EvidenceHandle evidence) {
        return new AnswerDocument(List.of(new AnswerStatement(new StatementId("fact"), StatementType.FACT,
                "Orders are created", Optional.of(new ClaimId("claim")), Set.of(evidence), Set.of())));
    }

    private static IssuedEvidence issuedEvidence(EvidenceHandle handle) {
        return issuedEvidence(handle, "rev-1");
    }

    private static IssuedEvidence issuedEvidence(EvidenceHandle handle, String revision) {
        return new IssuedEvidence(handle, new EvidenceRef("semantic", new RepositoryId("repo-1"),
                new RepositoryRevision(revision), new SemanticTarget(SemanticTargetKind.SYMBOL, "Orders#create", Optional.empty()),
                "Evidence", List.of(), new ArtifactRef("digest")));
    }

    private record Fixture(HandleBinding binding, CapabilityHandle capability, CandidateHandle candidate,
                           CapabilityDescriptor descriptor, boolean finalResponseMode, AgentValidationContext context) {
        private QueryAction query(List<CandidateHandle> handles, String depth) {
            return new QueryAction(capability, handles, "Find callers", Map.of("depth", depth), "Need call graph");
        }

        private AgentValidationContext contextWithCandidates(Map<CandidateHandle, IssuedCandidate> candidates) {
            return new AgentValidationContext(Map.of(capability, descriptor), candidates, Map.of(), Map.of(), binding,
                    new AttemptBudget(4, 0, 4, 0, 4, 0, 4, 0, 1, 0), finalResponseMode);
        }

        private AgentValidationContext contextWithEvidence(Map<EvidenceHandle, IssuedEvidence> evidence) {
            return new AgentValidationContext(Map.of(capability, descriptor), context.candidates(), evidence, Map.of(), binding,
                    new AttemptBudget(4, 0, 4, 0, 4, 0, 4, 0, 1, 0), finalResponseMode);
        }
    }
}
