package com.java.system.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.interaction.application.DeliveryWorkApplicationService;
import com.java.system.agent.interaction.application.DeliveryRetryPolicy;
import com.java.system.agent.interaction.application.SessionInboxProcessor;
import com.java.system.agent.interaction.application.StartupRecoveryApplicationService;
import com.java.system.agent.interaction.domain.RecoverySummary;
import com.java.system.agent.interaction.port.in.ProcessNextDeliveryUseCase;
import com.java.system.agent.interaction.port.in.ProcessNextInboxUseCase;
import com.java.system.agent.interaction.port.in.RecoverInterruptedWorkUseCase;
import com.java.system.agent.answering.application.AnalysisApplicationService;
import com.java.system.agent.answering.port.in.AnswerQuestionUseCase;
import com.java.system.agent.answering.port.out.AgentActionPort;
import com.java.system.agent.answering.port.out.AnswerVerificationPort;
import com.java.system.agent.slack.SlackSocketModeManager;
import com.java.system.agent.slack.delivery.SlackDeliveryAdapter;
import com.java.system.agent.worker.AgentWorkerManager;
import com.slack.api.bolt.App;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.methods.MethodsClient;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringBootTest(properties = "spring.profiles.active=dev")
class ApplicationStartupTest {

    @Test
    @DisplayName("the application context loads")
    void should_load_application_context() {
    }

    @Test
    @DisplayName("inactive development profile should not create chat memory beans")
    void should_not_create_chat_memory_beans(@Autowired ApplicationContext applicationContext) {
        assertThat(applicationContext.getBeansOfType(ChatMemory.class)).hasSize(0);
        assertThat(applicationContext.getBeansOfType(ChatMemoryRepository.class)).hasSize(0);
        assertThat(applicationContext.getBeansOfType(ChatModel.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(GoogleGenAiChatModel.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(Client.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(AnswerQuestionUseCase.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(SessionInboxProcessor.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(AgentActionPort.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(AnswerVerificationPort.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(JavaSemanticServiceHttpAdapter.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(Flyway.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(JdbcClient.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(TransactionTemplate.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(AgentWorkerManager.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(SlackSocketModeManager.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(SlackDeliveryAdapter.class)).isEmpty();
    }

    @Test
    @DisplayName("runtime profile loads Google configuration from profile-specific config data")
    void should_load_google_configuration_from_agent_runtime_profile() {
        new ApplicationContextRunner()
                .withInitializer(context -> {
                    context.getEnvironment().getPropertySources()
                            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    context.getEnvironment().getPropertySources()
                            .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                            "controlled-test-properties",
                            Map.of(
                                    "spring.profiles.active", "agent-runtime",
                                    "GOOGLE_API_KEY", "runtime-profile-key")));
                })
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getPropertySources())
                            .anyMatch(propertySource -> propertySource.getName()
                                    .contains("application-agent-runtime.yml"));
                    assertThat(context.getEnvironment().getProperty("spring.ai.model.chat"))
                            .isEqualTo("google-genai");
                    assertThat(context.getEnvironment().getProperty("spring.ai.google.genai.chat.model"))
                            .isEqualTo("gemini-3.1-flash-lite");
                    assertThat(context.getEnvironment().getProperty("spring.ai.google.genai.api-key"))
                            .isEqualTo("runtime-profile-key");
                });
    }

    @Test
    @DisplayName("agent-runtime keeps execution manual without Slack transport or background workers")
    void should_keep_agent_runtime_manual_without_slack_profile() {
        runtimeContextRunner("agent-runtime,slack-agent-test-infrastructure")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(AnalysisApplicationService.class)).hasSize(1);
                    assertThat(context.getBeansOfType(SessionInboxProcessor.class)).hasSize(1);
                    assertThat(context.getBeansOfType(AgentWorkerManager.class)).isEmpty();
                    assertThat(context.getBeansOfType(SlackSocketModeManager.class)).isEmpty();
                    assertThat(context.getBeansOfType(SlackDeliveryAdapter.class)).isEmpty();
                });
    }

    @Test
    @DisplayName("slack-agent profile group composes the worker, delivery, and Socket Mode graph with fake boundaries")
    void should_compose_slack_agent_profile_group() {
        slackAgentContextRunner()
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().acceptsProfiles("agent-runtime")).isTrue();
                    assertThat(context.getBeansOfType(AnalysisApplicationService.class)).hasSize(1);
                    assertThat(context.getBeansOfType(SessionInboxProcessor.class)).hasSize(1);
                    assertThat(context.getBeansOfType(DeliveryWorkApplicationService.class)).hasSize(1);
                    assertThat(context.getBeansOfType(StartupRecoveryApplicationService.class)).hasSize(1);
                    assertThat(context.getBeansOfType(SlackDeliveryAdapter.class)).hasSize(1);
                    assertThat(context.getBeansOfType(AgentWorkerManager.class)).hasSize(1);
                    assertThat(context.getBeansOfType(SlackSocketModeManager.class)).hasSize(1);
                    assertThat(context.getBean(DeliveryRetryPolicy.class).maximumAttempts()).isEqualTo(5);
                    assertThat(context.getBean(AgentWorkerManager.class).getPhase()).isEqualTo(100);
                    assertThat(context.getBean(SlackSocketModeManager.class).getPhase()).isEqualTo(200);
                });
    }

    @Test
    void should_apply_the_configured_delivery_maximum_attempts() {
        slackAgentContextRunner()
                .withPropertyValues("agent.worker.delivery-max-attempts=3")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DeliveryRetryPolicy.class).maximumAttempts()).isEqualTo(3);
                });
    }

    @Test
    void should_reject_nonpositive_delivery_maximum_attempt_configuration() {
        slackAgentContextRunner()
                .withPropertyValues("agent.worker.delivery-max-attempts=0")
                .run(context -> assertThat(context).hasFailed());
    }

    private ApplicationContextRunner slackAgentContextRunner() {
        return runtimeContextRunner("slack-agent,slack-agent-test-infrastructure")
                .withBean(App.class, () -> mock(App.class))
                .withBean(SocketModeApp.class, () -> mock(SocketModeApp.class))
                .withBean(MethodsClient.class, () -> mock(MethodsClient.class));
    }

    private ApplicationContextRunner runtimeContextRunner(String activeProfiles) {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    context.getEnvironment().getPropertySources()
                            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    context.getEnvironment().getPropertySources()
                            .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                            "controlled-profile-properties",
                            Map.of("spring.profiles.active", activeProfiles)));
                })
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Application.class, SlackAgentTestInfrastructureConfiguration.class)
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(Flyway.class, () -> mock(Flyway.class))
                .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
                .withBean(TransactionTemplate.class,
                        () -> new TransactionTemplate(new DataSourceTransactionManager(mock(DataSource.class))))
                .withPropertyValues(
                        "spring.ai.model.chat=none",
                        "agent.answer-verification.mode=llm",
                        "agent.codebase.base-url=http://localhost:8081",
                        "agent.codebase.api-token=test-token",
                        "agent.codebase.connect-timeout=2s",
                        "agent.codebase.read-timeout=15s",
                        "agent.worker.inbox-poll-interval=1s",
                        "agent.worker.delivery-poll-interval=1s",
                        "agent.worker.shutdown-grace-period=1s",
                        "agent.slack.app-token=xapp-test",
                        "agent.slack.bot-token=xoxb-test",
                        "agent.slack.bot-user-id=U_TEST");
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Profile("slack-agent-test-infrastructure")
    static class SlackAgentTestInfrastructureConfiguration {

        @Bean
        ChatModel chatModel() {
            return mock(ChatModel.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        @Primary
        RecoverInterruptedWorkUseCase testRecovery() {
            return recoveredAt -> new RecoverySummary(0, 0);
        }

        @Bean
        @Primary
        ProcessNextInboxUseCase testInboxProcessor() {
            return now -> Optional.empty();
        }

        @Bean
        @Primary
        ProcessNextDeliveryUseCase testDeliveryProcessor() {
            return now -> Optional.empty();
        }
    }
}
