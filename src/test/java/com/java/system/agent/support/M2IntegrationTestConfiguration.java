package com.java.system.agent.support;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 在 production composition 建構 HTTP adapter 前提供可控模型與已綁定的 RestClient builder
 */
@TestConfiguration(proxyBeanMethods = false)
@Profile("m2-flow-it")
public class M2IntegrationTestConfiguration {

    @Bean
    @Primary
    ControllableChatModel controllableChatModel(CallTimeline callTimeline) {
        return new ControllableChatModel(callTimeline);
    }

    @Bean
    CallTimeline callTimeline() {
        return new CallTimeline();
    }

    @Bean
    BoundCodebaseRestClientBuilder boundCodebaseRestClientBuilder() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new BoundCodebaseRestClientBuilder(builder, server);
    }

    @Bean("codebaseRestClientBuilder")
    @Primary
    @Qualifier("codebaseRestClientBuilder")
    RestClient.Builder codebaseRestClientBuilder(BoundCodebaseRestClientBuilder boundBuilder) {
        return boundBuilder.builder();
    }

    @Bean
    MockRestServiceServer mockRestServiceServer(BoundCodebaseRestClientBuilder boundBuilder) {
        return boundBuilder.server();
    }

    /**
     * 保留 builder 與 server 的同一綁定，避免 production adapter 先於 HTTP mock 建構
     */
    public record BoundCodebaseRestClientBuilder(RestClient.Builder builder, MockRestServiceServer server) {
    }
}
