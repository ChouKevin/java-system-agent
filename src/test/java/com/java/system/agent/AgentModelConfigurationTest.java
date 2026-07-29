package com.java.system.agent;

import com.java.system.agent.capability.planning.PlanningToolRegistry;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.capability.planning.PlanningToolSchemaFactory;
import com.java.system.agent.capability.planning.StrictPlanningToolDecoder;
import jakarta.validation.Validation;
import com.fasterxml.jackson.databind.ObjectMapper;
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
            ChatClient actionClient = context.getBean("agentActionChatClient", ChatClient.class);
            ChatClient verifierClient = context.getBean("agentVerifierChatClient", ChatClient.class);

            actionClient.prompt("first").call().content();

            assertThatThrownBy(() -> verifierClient.prompt("second").call().content())
                    .isInstanceOf(com.java.system.agent.runtime.port.out.ExternalExecutionDeferredException.class);
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
        ObjectMapper mapper = new ObjectMapper();
        PlanningToolSchemaFactory schemaFactory = new PlanningToolSchemaFactory(mapper);
        return new PlanningToolRegistry(List.of(), new StrictPlanningToolDecoder(
                mapper, Validation.buildDefaultValidatorFactory().getValidator()), new CanonicalCapabilityPayloadCodec(mapper), schemaFactory);
    }
}
