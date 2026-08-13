package com.java.system.agent.capability.planning;

import com.java.system.agent.answering.domain.action.ExecuteAction;
import com.java.system.agent.answering.domain.action.ExternalHttpMethod;
import com.java.system.agent.answering.domain.action.PlanAction;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.plan.InformationNeed;
import com.java.system.agent.answering.domain.plan.InformationNeedId;
import com.java.system.agent.answering.domain.plan.QuestionPlan;
import com.java.system.agent.answering.domain.run.ActionResult;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.ModelInteraction;
import com.java.system.agent.answering.port.out.AgentActionProposal;
import com.java.system.agent.answering.port.out.AgentPromptContext;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * execute_http planning registration 的嚴格解碼與獨立 issued 額度測試
 */
class ExecutePlanningToolRegistrationTest {

    @Test
    void mapsEverySupportedPreviewMethodWithMissingOptionalJsonBodyToExecuteAction() {
        PlanningToolRegistry registry = registry();
        List<ExternalHttpMethod> methods = List.of(
                ExternalHttpMethod.POST,
                ExternalHttpMethod.PUT,
                ExternalHttpMethod.PATCH,
                ExternalHttpMethod.DELETE);

        for (ExternalHttpMethod method : methods) {
            AgentActionProposal proposal = registry.interpretToolCall("execute_http", """
                    {"method":"%s","targetUrl":"https://service.example/orders/1","rationale":"Preview order update"}
                    """.formatted(method), context(0));

            assertThat(proposal).isEqualTo(new AgentActionProposal.Proposed(new ExecuteAction(
                    method,
                    "https://service.example/orders/1",
                    Optional.empty(),
                    "Preview order update")));
        }
    }

    @Test
    void issuesExecuteToolOnlyWhileIndependentExecuteBudgetRemains() {
        PlanningToolRegistry registry = registry();
        AgentPromptContext exhaustedQuery = context(0, 1, 3);
        AgentPromptContext reservedTerminalStep = context(0, 2, 3);
        AgentPromptContext exhaustedExecute = context(1);

        assertThat(registry.issuedRegistrations(exhaustedQuery))
                .extracting(registration -> registration.name())
                .containsExactly("execute_http");
        assertThat(registry.issuedRegistrations(reservedTerminalStep)).isEmpty();
        assertThat(registry.issuedRegistrations(exhaustedExecute)).isEmpty();
    }

    @Test
    void rejectsUnsupportedHttpMethodAndInvalidNestedJsonWithoutCreatingAnAction() {
        PlanningToolRegistry registry = registry();

        AgentActionProposal unsupportedMethod = registry.interpretToolCall("execute_http", """
                {"method":"GET","targetUrl":"https://service.example/orders","rationale":"Preview order read"}
                """, context(0));
        AgentActionProposal malformedBody = registry.interpretToolCall("execute_http", """
                {"method":"POST","targetUrl":"https://service.example/orders","jsonBody":"{]","rationale":"Preview order update"}
                """, context(0));
        AgentActionProposal emptyBody = registry.interpretToolCall("execute_http", """
                {"method":"POST","targetUrl":"https://service.example/orders","jsonBody":"","rationale":"Preview order update"}
                """, context(0));
        AgentActionProposal whitespaceBody = registry.interpretToolCall("execute_http", """
                {"method":"POST","targetUrl":"https://service.example/orders","jsonBody":"  ","rationale":"Preview order update"}
                """, context(0));

        assertThat(unsupportedMethod).isEqualTo(new AgentActionProposal.Malformed(
                "INVALID_TOOL_INPUT: tool=execute_http; reason=JSON_CONTRACT; "
                        + "invalidField=method; expectedJsonType=string"));
        assertThat(malformedBody).isEqualTo(new AgentActionProposal.Malformed("INVALID_TOOL_INPUT"));
        assertThat(emptyBody).isEqualTo(new AgentActionProposal.Malformed("INVALID_TOOL_INPUT"));
        assertThat(whitespaceBody).isEqualTo(new AgentActionProposal.Malformed("INVALID_TOOL_INPUT"));
    }

    @Test
    void rejectsExplicitNullUnknownFieldsAndTrailingTokensThroughTheStrictDecoder() {
        PlanningToolRegistry registry = registry();

        AgentActionProposal explicitNull = registry.interpretToolCall("execute_http", """
                {"method":"POST","targetUrl":"https://service.example/orders","jsonBody":null,"rationale":"Preview order update"}
                """, context(0));
        AgentActionProposal unknownField = registry.interpretToolCall("execute_http", """
                {"method":"POST","targetUrl":"https://service.example/orders","rationale":"Preview order update","unexpected":true}
                """, context(0));
        AgentActionProposal trailingTokens = registry.interpretToolCall("execute_http", """
                {"method":"POST","targetUrl":"https://service.example/orders","rationale":"Preview order update"} {}
                """, context(0));

        assertThat(explicitNull).isEqualTo(new AgentActionProposal.Malformed(
                "INVALID_TOOL_INPUT: tool=execute_http; reason=EXPLICIT_NULL"));
        assertThat(unknownField).isEqualTo(new AgentActionProposal.Malformed(
                "INVALID_TOOL_INPUT: tool=execute_http; reason=JSON_CONTRACT"));
        assertThat(trailingTokens).isEqualTo(new AgentActionProposal.Malformed(
                "INVALID_TOOL_INPUT: tool=execute_http; reason=JSON_CONTRACT"));
    }

    private static PlanningToolRegistry registry() {
        PlanningToolProvider provider = () -> List.of(new ExecutePlanningToolRegistration());
        return new PlanningToolRegistry(
                List.of(provider),
                new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator()),
                new CanonicalCapabilityPayloadCodec(Validation.buildDefaultValidatorFactory().getValidator()));
    }

    private static AgentPromptContext context(int usedExecuteExecutions) {
        return context(usedExecuteExecutions, 0, 0);
    }

    private static AgentPromptContext context(
            int usedExecuteExecutions,
            int usedAgentSteps,
            int usedQueryExecutions) {
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        QuestionPlan plan = new QuestionPlan(List.of(
                new InformationNeed(new InformationNeedId("scope"), "確認業務範圍")));
        return new AgentPromptContext(
                "Preview an order update",
                SessionHistory.empty(),
                new AnalysisRunId("run-1"),
                attemptId,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(
                        new ModelInteraction.ActionSelected(attemptId, new PlanAction(plan)),
                        new ModelInteraction.ActionResultRecorded(
                                attemptId, new ActionResult.QuestionPlanRecorded(plan))),
                Optional.empty(),
                new AttemptBudget(3, usedAgentSteps, 3, usedQueryExecutions, 1, usedExecuteExecutions, 3, 0, 1, 0));
    }
}
