package com.java.system.agent;

import com.java.system.agent.codeintelligence.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticFollowUpMapper;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticResultMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 由可替換 RestClient builder 建構 Java Semantic Service adapter 的根設定
 */
@Configuration(proxyBeanMethods = false)
@Profile("agent-runtime")
public final class AgentCodebaseConfiguration {

    @Bean
    RestClient codebaseRestClient(
            ObjectProvider<RestClient.Builder> builderProvider,
            AgentCodebaseProperties properties) {
        RestClient.Builder builder = builderProvider.getIfAvailable(() -> defaultBuilder(properties));
        return builder.baseUrl(properties.baseUrl())
                .defaultHeader("X-Api-Token", properties.apiToken())
                .build();
    }

    private RestClient.Builder defaultBuilder(AgentCodebaseProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().requestFactory(requestFactory);
    }

    @Bean
    JavaSemanticServiceHttpAdapter javaSemanticServiceHttpAdapter(
            RestClient codebaseRestClient,
            CanonicalCapabilityPayloadCodec payloadCodec) {
        JavaSemanticFollowUpMapper followUpMapper = new JavaSemanticFollowUpMapper(payloadCodec);
        JavaSemanticResultMapper resultMapper = new JavaSemanticResultMapper(followUpMapper);
        return new JavaSemanticServiceHttpAdapter(codebaseRestClient, resultMapper);
    }
}
