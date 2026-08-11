package com.java.system.agent.answering.application.validation;

import com.java.system.agent.answering.domain.action.ExecuteAction;
import com.java.system.agent.answering.domain.action.ExternalHttpMethod;
import com.java.system.agent.answering.domain.action.PlanAction;
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
import com.java.system.agent.answering.domain.plan.InformationNeed;
import com.java.system.agent.answering.domain.plan.InformationNeedId;
import com.java.system.agent.answering.domain.plan.QuestionPlan;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.ActionResult;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.ModelInteraction;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    void allowsAnEquivalentQueryToRetryAfterItsPreviousExecutionFailed() {
        Fixture fixture = fixture();
        CapabilityInputPayload payload = new CapabilityInputPayload("stable-payload");
        QueryAction previous = new QueryAction(
                fixture.capability(), List.of(new CandidateHandleRef(fixture.first().value())),
                "Initial wording", payload, "Initial rationale");
        QueryAction retry = new QueryAction(
                fixture.capability(), List.of(new CandidateHandleRef(fixture.first().value())),
                "Retry wording", payload, "Retry after a typed failure");
        List<ModelInteraction> interactions = List.of(
                new ModelInteraction.ActionSelected(fixture.binding().attemptId(), previous),
                new ModelInteraction.ActionResultRecorded(fixture.binding().attemptId(),
                        new ActionResult.QueryFailed(List.of("attempt-1:O1"), "dependency unavailable")));

        ActionValidation validation = validator.validate(
                retry, fixture.context(Map.of(fixture.first(), issued(fixture.first())), interactions));

        assertThat(validation).isEqualTo(new ActionValidation.Accepted(retry, List.of(issued(fixture.first()))));
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

    @Test
    void accepts_an_absolute_https_execute_preview_without_candidate_handles() {
        Fixture fixture = fixture();
        ExecuteAction action = execute("https://service.example/orders");

        ActionValidation validation = validator.validate(action, fixture.context(Map.of()));

        assertThat(validation).isInstanceOf(ActionValidation.Accepted.class);
        ActionValidation.Accepted accepted = (ActionValidation.Accepted) validation;
        assertThat(accepted.originalAction()).isSameAs(action);
        assertThat(accepted.resolvedCandidates()).isEmpty();
    }

    @Test
    void rejects_invalid_execute_preview_targets() {
        Fixture fixture = fixture();
        List<String> invalidTargets = List.of(
                "/orders",
                "ftp://service.example/orders",
                "https://user@service.example/orders",
                "https:/orders",
                "http:///orders",
                "https://?q=1",
                "https://[bad");

        for (String target : invalidTargets) {
            ExecuteAction action = execute(target);

            ActionValidation validation = validator.validate(action, fixture.context(Map.of()));

            assertThat(validation).isInstanceOf(ActionValidation.Rejected.class);
            ActionValidation.Rejected rejected = (ActionValidation.Rejected) validation;
            assertThat(rejected.code()).isEqualTo(ActionRejectionCode.INVALID_EXECUTE_TARGET);
            assertThat(rejected.originalAction()).isSameAs(action);
        }
    }

    @Test
    void rejects_execute_preview_when_execute_budget_is_exhausted_before_target_validation() {
        Fixture fixture = fixture();
        ExecuteAction action = execute("https:/orders");
        AttemptBudget exhaustedExecuteBudget = new AttemptBudget(4, 0, 4, 0, 1, 1, 4, 0, 2, 0);

        ActionValidation validation = validator.validate(action, fixture.context(Map.of(), exhaustedExecuteBudget));

        assertThat(validation).isInstanceOf(ActionValidation.Rejected.class);
        ActionValidation.Rejected rejected = (ActionValidation.Rejected) validation;
        assertThat(rejected.code()).isEqualTo(ActionRejectionCode.EXECUTE_BUDGET_EXHAUSTED);
        assertThat(rejected.originalAction()).isSameAs(action);
    }

    @Test
    void rejects_execute_preview_when_agent_step_budget_is_exhausted() {
        Fixture fixture = fixture();
        ExecuteAction action = execute("https://service.example/orders");
        AttemptBudget exhaustedAgentStepBudget = new AttemptBudget(4, 4, 4, 0, 1, 0, 4, 0, 2, 0);

        ActionValidation validation = validator.validate(action, fixture.context(Map.of(), exhaustedAgentStepBudget));

        assertThat(validation).isInstanceOf(ActionValidation.Rejected.class);
        ActionValidation.Rejected rejected = (ActionValidation.Rejected) validation;
        assertThat(rejected.code()).isEqualTo(ActionRejectionCode.BUDGET_EXHAUSTED);
        assertThat(rejected.originalAction()).isSameAs(action);
    }

    @Test
    void accepts_plan_without_candidate_handles_before_a_plan_exists() {
        Fixture fixture = fixture();
        PlanAction action = new PlanAction(plan());

        ActionValidation validation = validator.validate(action, fixture.contextWithoutPlan(Map.of()));

        assertThat(validation).isEqualTo(new ActionValidation.Accepted(action, List.of()));
    }

    @Test
    void rejects_non_plan_actions_before_a_plan_exists_and_plan_after_it_exists() {
        Fixture fixture = fixture();
        ExecuteAction execute = execute("https://service.example/orders");
        PlanAction plan = new PlanAction(plan());

        assertThat(validator.validate(execute, fixture.contextWithoutPlan(Map.of())))
                .isEqualTo(new ActionValidation.Rejected(ActionRejectionCode.QUESTION_PLAN_REQUIRED,
                        ActionRejectionCode.QUESTION_PLAN_REQUIRED.name(), execute));
        assertThat(validator.validate(plan, fixture.context(Map.of())))
                .isEqualTo(new ActionValidation.Rejected(ActionRejectionCode.QUESTION_PLAN_ALREADY_EXISTS,
                        ActionRejectionCode.QUESTION_PLAN_ALREADY_EXISTS.name(), plan));
    }

    private static ExecuteAction execute(String target) {
        return new ExecuteAction(ExternalHttpMethod.POST, target, Optional.empty(), "preview external request");
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
            return context(candidates, List.of());
        }

        private AgentValidationContext context(
                Map<CandidateHandle, IssuedCandidate> candidates,
                List<ModelInteraction> modelInteractions) {
            return context(
                    candidates, modelInteractions,
                    new AttemptBudget(4, 0, 4, 0, 1, 0, 4, 0, 2, 0), Optional.of(plan()));
        }

        private AgentValidationContext context(
                Map<CandidateHandle, IssuedCandidate> candidates,
                AttemptBudget budget) {
            return context(candidates, List.of(), budget, Optional.of(plan()));
        }

        private AgentValidationContext contextWithoutPlan(Map<CandidateHandle, IssuedCandidate> candidates) {
            return context(
                    candidates, List.of(),
                    new AttemptBudget(4, 0, 4, 0, 1, 0, 4, 0, 2, 0), Optional.empty());
        }

        private AgentValidationContext context(
                Map<CandidateHandle, IssuedCandidate> candidates,
                List<ModelInteraction> modelInteractions,
                AttemptBudget budget,
                Optional<QuestionPlan> questionPlan) {
            CapabilityPolicy policy = new CapabilityPolicy("callers", "v1", Set.of(CandidateKind.ROUTE), 1, 2);
            return new AgentValidationContext(
                    Map.of(capability, policy), candidates, Map.of(), Map.of(), modelInteractions, binding,
                    budget, questionPlan);
        }
    }

    private static QuestionPlan plan() {
        return new QuestionPlan(List.of(new InformationNeed(new InformationNeedId("need-1"), "Trace the route")));
    }
}
