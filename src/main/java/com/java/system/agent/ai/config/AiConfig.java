package com.java.system.agent.ai.config;

import java.io.IOException;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatProperties;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiConnectionProperties;
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;

import io.micrometer.observation.ObservationRegistry;

@Configuration
@EnableConfigurationProperties(AgentLoopProperties.class)
public class AiConfig {

    @Bean
    public ChatMemoryRepository chatMemoryRepository() {
        return new InMemoryChatMemoryRepository();
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(20)
                .build();
    }

    @Bean
    @Profile({"uat", "pro"})
    public Client googleGenAiClient(GoogleGenAiConnectionProperties connectionProperties) throws IOException {
        Client.Builder clientBuilder = Client.builder();

        if (StringUtils.hasText(connectionProperties.getApiKey())) {
            clientBuilder.apiKey(connectionProperties.getApiKey());
        } else {
            Assert.hasText(connectionProperties.getProjectId(), "Google GenAI project-id must be set!");
            Assert.hasText(connectionProperties.getLocation(), "Google GenAI location must be set!");

            clientBuilder.project(connectionProperties.getProjectId())
                .location(connectionProperties.getLocation())
                .vertexAI(true);

            if (connectionProperties.getCredentialsUri() != null) {
                GoogleCredentials.fromStream(connectionProperties.getCredentialsUri().getInputStream());
            }
        }

        return clientBuilder.build();
    }

    @Bean
    @Primary
    @Profile({"uat", "pro"})
    public GoogleGenAiChatModel googleGenAiChatModel(Client googleGenAiClient,
            GoogleGenAiChatProperties chatProperties,
            ToolCallingManager toolCallingManager,
            ApplicationContext context,
            RetryTemplate retryTemplate,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<ChatModelObservationConvention> observationConvention,
            ObjectProvider<ToolExecutionEligibilityPredicate> toolExecutionEligibilityPredicate) {

        GoogleGenAiChatModel chatModel = GoogleGenAiChatModel.builder()
            .genAiClient(googleGenAiClient)
            .defaultOptions(chatProperties.getOptions())
            .toolCallingManager(toolCallingManager)
            .toolExecutionEligibilityPredicate(
                    toolExecutionEligibilityPredicate.getIfUnique(() -> new DefaultToolExecutionEligibilityPredicate()))
            .retryTemplate(retryTemplate)
            .observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
            .build();

        observationConvention.ifAvailable(chatModel::setObservationConvention);
        return chatModel;
    }

}
