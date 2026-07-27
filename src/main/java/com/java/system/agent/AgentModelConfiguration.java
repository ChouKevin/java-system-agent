package com.java.system.agent;

import com.java.system.agent.model.action.SpringAiAgentActionAdapter;
import com.java.system.agent.model.verification.AnswerVerificationDispatcher;
import com.java.system.agent.model.verification.ContractOnlyAnswerVerificationAdapter;
import com.java.system.agent.model.verification.SpringAiAnswerVerificationAdapter;
import com.java.system.agent.runtime.port.out.AgentActionPort;
import com.java.system.agent.runtime.port.out.AnswerVerificationPort;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 由 Spring AI provider bean 組裝 action 與 verifier adapter 的根設定
 */
@Configuration(proxyBeanMethods = false)
@Profile("agent-runtime")
public final class AgentModelConfiguration {

    @Bean("agentActionChatClient")
    ChatClient agentActionChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    @Bean("agentVerifierChatClient")
    ChatClient agentVerifierChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    @Bean
    AgentActionPort agentActionPort(@Qualifier("agentActionChatClient") ChatClient chatClient) {
        return new SpringAiAgentActionAdapter(chatClient);
    }

    @Bean
    AnswerVerificationPort answerVerificationPort(@Qualifier("agentVerifierChatClient") ChatClient chatClient) {
        return new AnswerVerificationDispatcher(
                new SpringAiAnswerVerificationAdapter(chatClient),
                new ContractOnlyAnswerVerificationAdapter());
    }
}
