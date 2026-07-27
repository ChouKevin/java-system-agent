package com.java.system.agent;

import com.java.system.agent.codebase.semantic.JavaSemanticServiceHttpAdapter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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

    @Bean("codebaseRestClientBuilder")
    @Qualifier("codebaseRestClientBuilder")
    @ConditionalOnMissingBean(name = "codebaseRestClientBuilder")
    RestClient.Builder codebaseRestClientBuilder(
            RestClient.Builder bootBuilder,
            AgentCodebaseProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return bootBuilder.clone().requestFactory(requestFactory);
    }

    @Bean
    RestClient codebaseRestClient(
            @Qualifier("codebaseRestClientBuilder") RestClient.Builder builder,
            AgentCodebaseProperties properties) {
        return builder.baseUrl(properties.baseUrl())
                .defaultHeader("X-Api-Token", properties.apiToken())
                .build();
    }

    @Bean
    JavaSemanticServiceHttpAdapter javaSemanticServiceHttpAdapter(RestClient codebaseRestClient) {
        return new JavaSemanticServiceHttpAdapter(codebaseRestClient);
    }
}
