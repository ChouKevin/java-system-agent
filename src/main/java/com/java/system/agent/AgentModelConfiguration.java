package com.java.system.agent;

import com.java.system.agent.model.action.SpringAiAgentActionAdapter;
import com.java.system.agent.capability.planning.PlanningToolRegistry;
import com.java.system.agent.model.quota.ModelInputTokenEstimator;
import com.java.system.agent.model.quota.ModelQuotaGate;
import com.java.system.agent.model.quota.ModelQuotaWindow;
import com.java.system.agent.model.quota.ModelRetryAfterExtractor;
import com.java.system.agent.model.verification.AnswerVerificationDispatcher;
import com.java.system.agent.model.verification.ContractOnlyAnswerVerificationAdapter;
import com.java.system.agent.model.verification.SpringAiAnswerVerificationAdapter;
import com.java.system.agent.runtime.port.out.AgentActionPort;
import com.java.system.agent.runtime.port.out.AnswerVerificationPort;
import com.java.system.agent.model.ModelLifecycleMetrics;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;

/**
 * 由 Spring AI provider bean 組裝 action 與 verifier adapter 的根設定
 */
@Configuration(proxyBeanMethods = false)
@Profile("agent-runtime")
@EnableConfigurationProperties(AgentModelRateLimitProperties.class)
public final class AgentModelConfiguration {

    @Bean
    ModelQuotaWindow modelQuotaWindow(AgentModelRateLimitProperties properties) {
        return new ModelQuotaWindow(properties.requestsPerMinute(), properties.inputTokensPerMinute(),
                properties.requestsPerDay());
    }

    @Bean
    ModelInputTokenEstimator modelInputTokenEstimator() {
        return new ModelInputTokenEstimator();
    }

    @Bean
    ModelRetryAfterExtractor modelRetryAfterExtractor() {
        return new ModelRetryAfterExtractor();
    }

    @Bean("agentModelQuotaGate")
    ModelQuotaGate agentModelQuotaGate(
            ConfigurableListableBeanFactory beanFactory,
            ModelQuotaWindow window,
            ModelInputTokenEstimator tokenEstimator,
            ModelRetryAfterExtractor retryAfterExtractor,
            AgentModelRateLimitProperties properties,
            ObjectProvider<ModelLifecycleMetrics> metricsProvider) {
        return new ModelQuotaGate(providerChatModel(beanFactory), window, tokenEstimator, retryAfterExtractor,
                Clock.systemUTC(),
                properties.providerRetryFallback(), metricsProvider.getIfAvailable(() -> ModelLifecycleMetrics.NO_OP));
    }

    private ChatModel providerChatModel(ConfigurableListableBeanFactory beanFactory) {
        List<String> providerNames = Arrays.stream(beanFactory.getBeanNamesForType(ChatModel.class, false, false))
                .filter(beanName -> !"agentModelQuotaGate".equals(beanName))
                .sorted()
                .toList();
        if (providerNames.isEmpty()) {
            throw new NoSuchBeanDefinitionException(ChatModel.class);
        }
        List<String> primaryProviderNames = providerNames.stream()
                .filter(beanName -> beanFactory.containsBeanDefinition(beanName)
                        && beanFactory.getBeanDefinition(beanName).isPrimary())
                .toList();
        if (primaryProviderNames.size() == 1) {
            return beanFactory.getBean(primaryProviderNames.getFirst(), ChatModel.class);
        }
        if (providerNames.size() > 1) {
            throw new NoUniqueBeanDefinitionException(ChatModel.class, providerNames);
        }
        return beanFactory.getBean(providerNames.getFirst(), ChatModel.class);
    }

    @Bean("agentActionChatClient")
    ChatClient agentActionChatClient(@Qualifier("agentModelQuotaGate") ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    @Bean("agentVerifierChatClient")
    ChatClient agentVerifierChatClient(@Qualifier("agentModelQuotaGate") ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    @Bean
    AgentActionPort agentActionPort(@Qualifier("agentActionChatClient") ChatClient chatClient,
                                    PlanningToolRegistry registry) {
        return new SpringAiAgentActionAdapter(chatClient, registry);
    }

    @Bean
    AnswerVerificationPort answerVerificationPort(@Qualifier("agentVerifierChatClient") ChatClient chatClient) {
        return new AnswerVerificationDispatcher(
                new SpringAiAnswerVerificationAdapter(chatClient),
                new ContractOnlyAnswerVerificationAdapter());
    }
}
