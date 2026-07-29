package com.java.system.agent.answering.application.validation;

import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.candidate.RouteCandidate;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.handle.CandidateHandleRef;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型 raw candidate handle value 的 answering 驗證測試
 */
class AgentActionValidatorTest {

    private final AgentActionValidator validator = new AgentActionValidator();

    @Test
    void resolves_current_issued_candidate_values_in_model_order_without_replacing_action() {
        Fixture fixture = fixture();
        CandidateHandle second = candidate("candidate-2");
        QueryAction action = new QueryAction(fixture.capability(),
                List.of(new CandidateHandleRef(second.value()), new CandidateHandleRef(fixture.first().value())),
                "resolve route", new CapabilityInputPayload("{}"), "inspect both candidates");

        ActionValidation validation = validator.validate(action, fixture.context(Map.of(
                fixture.first(), issued(fixture.first()), second, issued(second))));

        assertThat(validation).isInstanceOf(ActionValidation.Accepted.class);
        ActionValidation.Accepted accepted = (ActionValidation.Accepted) validation;
        assertThat(accepted.originalAction()).isSameAs(action);
        assertThat(accepted.resolvedCandidates()).containsExactly(issued(second), issued(fixture.first()));
    }

    @Test
    void rejects_an_unmatched_raw_candidate_value() {
        Fixture fixture = fixture();
        QueryAction action = new QueryAction(fixture.capability(), List.of(new CandidateHandleRef("unknown")),
                "resolve route", new CapabilityInputPayload("{}"), "inspect candidate");

        assertThat(validator.validate(action, fixture.context(Map.of(fixture.first(), issued(fixture.first())))))
                .isEqualTo(new ActionValidation.Rejected(ActionRejectionCode.UNKNOWN_CANDIDATE,
                        ActionRejectionCode.UNKNOWN_CANDIDATE.name(), action));
    }

    @Test
    void rejects_candidate_values_that_resolve_to_an_incompatible_kind() {
        Fixture fixture = fixture();
        CandidateHandle repository = new CandidateHandle("repository", fixture.binding(), CandidateKind.REPOSITORY);
        QueryAction action = new QueryAction(fixture.capability(), List.of(new CandidateHandleRef(repository.value())),
                "resolve route", new CapabilityInputPayload("{}"), "inspect candidate");
        IssuedCandidate issued = new IssuedCandidate(repository,
                new com.java.system.agent.answering.domain.candidate.RepositoryCandidate(new RepositoryId("repo-1"), "Orders"));

        assertThat(validator.validate(action, fixture.context(Map.of(repository, issued))))
                .isEqualTo(new ActionValidation.Rejected(ActionRejectionCode.INCOMPATIBLE_CANDIDATE_KIND,
                        ActionRejectionCode.INCOMPATIBLE_CANDIDATE_KIND.name(), action));
    }

    private static Fixture fixture() {
        HandleBinding binding = binding();
        CapabilityHandle capability = new CapabilityHandle("capability-1", binding);
        return new Fixture(binding, capability, candidate("candidate-1"));
    }

    private static CandidateHandle candidate(String value) {
        return new CandidateHandle(value, binding(), CandidateKind.ROUTE);
    }

    private static HandleBinding binding() {
        RepositoryId repository = new RepositoryId("repo-1");
        return new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"),
                RevisionVector.empty().pin(repository, new RepositoryRevision("rev-1")));
    }

    private static IssuedCandidate issued(CandidateHandle handle) {
        return new IssuedCandidate(handle, new RouteCandidate(new RepositoryId("repo-1"),
                new RepositoryRevision("rev-1"), "/orders", "Orders route"));
    }

    private record Fixture(HandleBinding binding, CapabilityHandle capability, CandidateHandle first) {
        private AgentValidationContext context(Map<CandidateHandle, IssuedCandidate> candidates) {
            CapabilityPolicy policy = new CapabilityPolicy("callers", "v1", Set.of(CandidateKind.ROUTE), 1, 2);
            return new AgentValidationContext(Map.of(capability, policy), candidates, Map.of(), Map.of(), binding,
                    new AttemptBudget(4, 0, 4, 0, 4, 0, 2, 0));
        }
    }
}
