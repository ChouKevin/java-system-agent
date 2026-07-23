package com.java.system.agent.ai.config;

import java.io.IOException;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatProperties;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiConnectionProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;

import io.micrometer.observation.ObservationRegistry;

@Configuration
@EnableConfigurationProperties({AgentLoopProperties.class, AgentMemoryProperties.class})
public class AiConfig {

    @Bean
    public ChatMemoryRepository chatMemoryRepository(AgentMemoryProperties memoryProperties) {
        return new LruChatMemoryRepository(memoryProperties.maxConversations());
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
        Client.Builder clientBuilder = Client.builder()
                .httpOptions(HttpOptions.builder().timeout(180_000).build());

        if (StringUtils.hasText(connectionProperties.getApiKey())) {
            clientBuilder.apiKey(connectionProperties.getApiKey());
        } else {
            Assert.hasText(connectionProperties.getProjectId(), "Google GenAI project-id must be set!");
            Assert.hasText(connectionProperties.getLocation(), "Google GenAI location must be set!");

            clientBuilder.project(connectionProperties.getProjectId())
                .location(connectionProperties.getLocation())
                .vertexAI(true);

            if (connectionProperties.getCredentialsUri() != null) {
                GoogleCredentials credentials = GoogleCredentials.fromStream(
                        connectionProperties.getCredentialsUri().getInputStream());
                clientBuilder.credentials(credentials);
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
            ObjectProvider<ChatModelObservationConvention> observationConvention) {

        GoogleGenAiChatModel chatModel = GoogleGenAiChatModel.builder()
            .genAiClient(googleGenAiClient)
            .options(chatProperties.toOptions())
            .toolCallingManager(toolCallingManager)
            .retryTemplate(retryTemplate)
            .observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
            .build();

        observationConvention.ifAvailable(chatModel::setObservationConvention);
        return chatModel;
    }

}
