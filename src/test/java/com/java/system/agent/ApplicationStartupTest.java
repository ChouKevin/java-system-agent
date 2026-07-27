package com.java.system.agent;

import com.google.genai.Client;
import com.java.system.agent.codebase.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.inbox.application.SessionInboxProcessor;
import com.java.system.agent.runtime.port.in.AnswerQuestionUseCase;
import com.java.system.agent.runtime.port.out.AgentActionPort;
import com.java.system.agent.runtime.port.out.AnswerVerificationPort;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

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
}
