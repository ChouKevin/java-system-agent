package com.java.system.agent;

import com.java.system.agent.capability.planning.PlanningToolRegistry;
import com.java.system.agent.capability.planning.PlanningToolProvider;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.capability.planning.CorePlanningToolProvider;
import com.java.system.agent.capability.planning.ExecutePlanningToolRegistration;
import com.java.system.agent.capability.planning.QueryPlanningSelection;
import com.java.system.agent.capability.planning.QueryPlanningToolRegistration;
import com.java.system.agent.capability.planning.StrictPlanningToolDecoder;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.model.prompt.PromptResourceCatalog;
import jakarta.validation.Validation;
import com.java.system.agent.model.quota.ModelQuotaGate;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentModelConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AgentModelConfiguration.class)
            .withBean(ChatModel.class, CountingChatModel::new)
            .withBean(PlanningToolRegistry.class, AgentModelConfigurationTest::planningToolRegistry)
            .withPropertyValues(
                    "spring.profiles.active=agent-runtime",
                    "agent.model.rate-limit.requests-per-minute=1",
                    "agent.model.rate-limit.input-tokens-per-minute=100",
                    "agent.model.rate-limit.requests-per-day=500",
                    "agent.model.rate-limit.provider-retry-fallback=1m");

    @Test
    void sharesOneQuotaGateBetweenActionAndVerifierClientsWithoutObservabilityComposition() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(ModelQuotaGate.class)).hasSize(1);
            assertThat(context.getBean(PromptResourceCatalog.class).catalogDigest()).matches("[0-9a-f]{64}");
            ChatClient actionClient = context.getBean("agentActionChatClient", ChatClient.class);
            ChatClient verifierClient = context.getBean("agentVerifierChatClient", ChatClient.class);

            actionClient.prompt("first").call().content();

            assertThatThrownBy(() -> verifierClient.prompt("second").call().content())
                    .isInstanceOf(com.java.system.agent.answering.port.out.ExternalExecutionDeferredException.class);
        });
    }

    @Test
    void rejectsNonPositiveRateLimitProperties() {
        contextRunner.withPropertyValues("agent.model.rate-limit.requests-per-minute=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("requestsPerMinute");
                });
        contextRunner.withPropertyValues("agent.model.rate-limit.provider-retry-fallback=0s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("provider retry fallback");
                });
    }

    @Test
    void rejectsAMissingProviderChatModel() {
        new ApplicationContextRunner()
                .withUserConfiguration(AgentModelConfiguration.class)
                .withBean(PlanningToolRegistry.class, AgentModelConfigurationTest::planningToolRegistry)
                .withPropertyValues(
                        "spring.profiles.active=agent-runtime",
                        "agent.model.rate-limit.requests-per-minute=15",
                        "agent.model.rate-limit.input-tokens-per-minute=100",
                        "agent.model.rate-limit.requests-per-day=500",
                        "agent.model.rate-limit.provider-retry-fallback=1m")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("ChatModel");
                });
    }

    @Test
    void wrapsThePrimaryChatModelWhenMultipleProvidersAreConfigured() {
        new ApplicationContextRunner()
                .withUserConfiguration(AgentModelConfiguration.class)
                .withBean("primaryChatModel", ChatModel.class, CountingChatModel::new,
                        beanDefinition -> beanDefinition.setPrimary(true))
                .withBean("secondaryChatModel", ChatModel.class, CountingChatModel::new)
                .withBean(PlanningToolRegistry.class, AgentModelConfigurationTest::planningToolRegistry)
                .withPropertyValues(
                        "spring.profiles.active=agent-runtime",
                        "agent.model.rate-limit.requests-per-minute=15",
                        "agent.model.rate-limit.input-tokens-per-minute=100",
                        "agent.model.rate-limit.requests-per-day=500",
                        "agent.model.rate-limit.provider-retry-fallback=1m")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ChatClient actionClient = context.getBean("agentActionChatClient", ChatClient.class);

                    actionClient.prompt("primary provider").call().content();

                    assertThat(context.getBean("primaryChatModel", CountingChatModel.class).calls()).isEqualTo(1);
                    assertThat(context.getBean("secondaryChatModel", CountingChatModel.class).calls()).isZero();
                });
    }

    private static final class CountingChatModel implements ChatModel {

        private int calls;

        @Override
        public ChatResponse call(Prompt prompt) {
            calls++;
            return new ChatResponse(List.of(new Generation(new AssistantMessage("response"))));
        }

        private int calls() {
            return calls;
        }
    }

    private static PlanningToolRegistry planningToolRegistry() {
        CanonicalCapabilityPayloadCodec payloadCodec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        PlanningToolProvider queryProvider = () -> List.of(
                query("codebase_outgoing_call_graph", CandidateKind.SEMANTIC_TARGET, Optional.empty()),
                query("codebase_incoming_call_graph", CandidateKind.SEMANTIC_TARGET, Optional.empty()),
                query("codebase_discover_method_implementations", CandidateKind.FOLLOW_UP, Optional.empty()),
                query("codebase_find_internal_references", CandidateKind.FOLLOW_UP, Optional.empty()),
                query("codebase_get_method_source", CandidateKind.SEMANTIC_TARGET, Optional.empty()));
        PlanningToolProvider executeProvider = () -> List.of(new ExecutePlanningToolRegistration());
        return new PlanningToolRegistry(List.of(new CorePlanningToolProvider(), executeProvider, queryProvider),
                new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator()), payloadCodec);
    }

    private static QueryPlanningToolRegistration<TestPlanningInput, String> query(
            String capabilityName,
            CandidateKind candidateKind,
            Optional<String> guidanceId) {
        CapabilityPolicy policy = new CapabilityPolicy(capabilityName, "v1", Set.of(candidateKind), 1, 1);
        return new QueryPlanningToolRegistration<>(policy, TestPlanningInput.class, String.class,
                input -> new QueryPlanningSelection<>(List.of(), "question", "rationale", input.value()),
                (context, input) -> null,
                new CanonicalCapabilityPayloadCodec(Validation.buildDefaultValidatorFactory().getValidator()), guidanceId);
    }

    private record TestPlanningInput(String value) {
    }
}
