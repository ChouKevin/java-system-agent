package com.java.system.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.codebase.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.inbox.application.SessionInboxProcessor;
import com.java.system.agent.runtime.port.in.AnswerQuestionUseCase;
import com.java.system.agent.runtime.port.out.AgentActionPort;
import com.java.system.agent.runtime.port.out.AnswerVerificationPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AgentRuntimeConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(Application.class, TestInfrastructureConfiguration.class)
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(Flyway.class, () -> mock(Flyway.class))
            .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
            .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class))
            .withPropertyValues(
                    "spring.profiles.active=test-infrastructure",
                    "spring.ai.model.chat=none",
                    "agent.answer-verification.mode=llm",
                    "agent.codebase.base-url=http://localhost:8081",
                    "agent.codebase.api-token=test-token",
                    "agent.codebase.connect-timeout=2s",
                    "agent.codebase.read-timeout=15s");

    @Test
    @DisplayName("inactive profile leaves every production Agent boundary absent")
    void shouldLeaveProductionAgentBoundariesAbsentWithoutRuntimeProfile() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(AnswerQuestionUseCase.class)).isEmpty();
            assertThat(context.getBeansOfType(SessionInboxProcessor.class)).isEmpty();
            assertThat(context.getBeansOfType(AgentActionPort.class)).isEmpty();
            assertThat(context.getBeansOfType(AnswerVerificationPort.class)).isEmpty();
            assertThat(context.getBeansOfType(JavaSemanticServiceHttpAdapter.class)).isEmpty();
        });
    }

    @Test
    @DisplayName("runtime profile composes exactly one use case and processor through early replacement persistence boundaries")
    void shouldComposeRuntimeThroughEarlyReplacementPersistenceBoundaries() {
        contextRunner.withPropertyValues("spring.profiles.active=agent-runtime,test-infrastructure").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(AnswerQuestionUseCase.class)).hasSize(1);
            assertThat(context.getBeansOfType(SessionInboxProcessor.class)).hasSize(1);
            assertThat(context.getBeansOfType(AgentActionPort.class)).hasSize(1);
            assertThat(context.getBeansOfType(AnswerVerificationPort.class)).hasSize(1);
            assertThat(context.getBeansOfType(JavaSemanticServiceHttpAdapter.class)).hasSize(1);

            MockRestServiceServer server = context.getBean(MockRestServiceServer.class);
            server.expect(requestTo("http://localhost:8081/v1/repositories"))
                    .andExpect(header("X-Api-Token", "test-token"))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

            context.getBean(JavaSemanticServiceHttpAdapter.class).availableRepositories();

            server.verify();
        });
    }

    @Test
    @DisplayName("runtime profile rejects missing Agent-owned configuration before constructing the graph")
    void shouldRejectMissingAgentOwnedConfiguration() {
        contextRunner.withPropertyValues(
                "spring.profiles.active=agent-runtime,test-infrastructure",
                "agent.answer-verification.mode=",
                "agent.codebase.base-url=",
                "agent.codebase.api-token=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("agent");
        });
    }

    @Test
    @DisplayName("runtime profile rejects every blank required Agent-owned property")
    void shouldRejectBlankAgentOwnedProperties() {
        assertStartupFailure("agent.answer-verification.mode=", "agent.answer-verification");
        assertStartupFailure("agent.codebase.base-url=", "agent.codebase");
        assertStartupFailure("agent.codebase.api-token=", "agent.codebase");
    }

    @Test
    @DisplayName("runtime profile rejects missing database configuration before constructing persistence infrastructure")
    void shouldRejectMissingDatabaseConfiguration() {
        new ApplicationContextRunner()
                .withUserConfiguration(Application.class, TestInfrastructureConfiguration.class)
                .withPropertyValues(
                        "spring.profiles.active=agent-runtime,test-infrastructure",
                        "spring.ai.model.chat=none",
                        "agent.answer-verification.mode=llm",
                        "agent.codebase.base-url=http://localhost:8081",
                        "agent.codebase.api-token=test-token",
                        "agent.codebase.connect-timeout=2s",
                        "agent.codebase.read-timeout=15s")
                .run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("spring.datasource");
        });
    }

    @Test
    @DisplayName("runtime profile requires a Spring AI ChatModel without creating a provider client")
    void shouldRequireInjectedChatModel() {
        new ApplicationContextRunner()
                .withUserConfiguration(Application.class, NoChatModelInfrastructureConfiguration.class)
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(Flyway.class, () -> mock(Flyway.class))
                .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
                .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class))
                .withPropertyValues(
                        "spring.profiles.active=agent-runtime,no-chat-model-test",
                        "spring.ai.model.chat=none",
                        "agent.answer-verification.mode=llm",
                        "agent.codebase.base-url=http://localhost:8081",
                        "agent.codebase.api-token=test-token",
                        "agent.codebase.connect-timeout=2s",
                        "agent.codebase.read-timeout=15s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("ChatModel");
                });
    }

    private void assertStartupFailure(String property, String expectedPropertyPrefix) {
        contextRunner.withPropertyValues("spring.profiles.active=agent-runtime,test-infrastructure", property)
                .run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining(expectedPropertyPrefix);
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Profile("test-infrastructure")
    static class TestInfrastructureConfiguration {

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
        @Qualifier("codebaseRestClientBuilder")
        RestClient.Builder testCodebaseRestClientBuilder(BoundRestClientBuilder boundRestClientBuilder) {
            return boundRestClientBuilder.builder();
        }

        @Bean
        BoundRestClientBuilder boundRestClientBuilder() {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            return new BoundRestClientBuilder(builder, server);
        }

        @Bean
        MockRestServiceServer mockRestServiceServer(BoundRestClientBuilder boundRestClientBuilder) {
            return boundRestClientBuilder.server();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Profile("no-chat-model-test")
    static class NoChatModelInfrastructureConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        @Primary
        @Qualifier("codebaseRestClientBuilder")
        RestClient.Builder testCodebaseRestClientBuilder() {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer.bindTo(builder).build();
            return builder;
        }
    }

    private record BoundRestClientBuilder(RestClient.Builder builder, MockRestServiceServer server) {
    }
}
