package com.java.system.agent.slack.config;

import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SlackConfig {

    @Value("${slack.bot-token}")
    private String botToken;

    @Value("${slack.signing-secret}")
    private String signingSecret;

    @Bean
    public App slackApp() {
        AppConfig config = AppConfig.builder()
                .singleTeamBotToken(botToken)
                .signingSecret(signingSecret)
                .build();
        return new App(config);
    }
}
