package com.java.system.agent;

import com.java.system.agent.interaction.application.DeliveryProcessor;
import com.java.system.agent.interaction.application.DeliveryRetryPolicy;
import com.java.system.agent.interaction.application.DeliveryWorkApplicationService;
import com.java.system.agent.interaction.application.ClaimAdmissionCoordinator;
import com.java.system.agent.interaction.application.AgentOperationsApplicationService;
import com.java.system.agent.interaction.application.InboxLifecycleMetrics;
import com.java.system.agent.interaction.application.SourceAcceptanceApplicationService;
import com.java.system.agent.interaction.application.StartupRecoveryApplicationService;
import com.java.system.agent.interaction.port.in.ProcessNextDeliveryUseCase;
import com.java.system.agent.interaction.port.in.ProcessNextInboxUseCase;
import com.java.system.agent.interaction.port.in.RecoverInterruptedWorkUseCase;
import com.java.system.agent.interaction.port.in.StopClaimingUseCase;
import com.java.system.agent.interaction.port.in.ReadAgentOperationsUseCase;
import com.java.system.agent.persistence.jdbc.PostgresDeliveryOutboxAdapter;
import com.java.system.agent.persistence.jdbc.PostgresAgentOperationsAdapter;
import com.java.system.agent.persistence.jdbc.PostgresSessionInboxAdapter;
import com.java.system.agent.slack.SlackSocketModeManager;
import com.java.system.agent.slack.delivery.SlackChannelRateGate;
import com.java.system.agent.slack.delivery.SlackDeliveryAdapter;
import com.java.system.agent.slack.source.SlackAppMentionHandler;
import com.java.system.agent.slack.source.SlackDeliveryMetadataGuard;
import com.java.system.agent.slack.source.SlackMentionNormalizer;
import com.java.system.agent.slack.source.SlackSourceIdentityCodec;
import com.java.system.agent.worker.AgentWorkerManager;
import com.java.system.agent.slack.SlackLifecycleMetrics;
import com.slack.api.Slack;
import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.auth.AuthTestRequest;
import com.slack.api.methods.response.auth.AuthTestResponse;
import com.slack.api.model.event.AppMentionEvent;
import com.slack.api.socket_mode.SocketModeClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * 在 slack-agent profile 中組裝 Slack transport 與背景 worker 的 privileged bootstrap
 */
@Configuration(proxyBeanMethods = false)
@Profile("slack-agent")
@EnableConfigurationProperties({AgentWorkerProperties.class, SlackAgentProperties.class})
public final class SlackAgentConfiguration {

    @Bean
    @ConditionalOnMissingBean(MethodsClient.class)
    MethodsClient slackMethodsClient(SlackAgentProperties properties) {
        return Slack.getInstance().methods(properties.botToken());
    }

    @Bean
    String slackBotUserId(SlackAgentProperties properties, MethodsClient methodsClient) {
        if (StringUtils.hasText(properties.botUserId())) {
            return properties.botUserId();
        }
        try {
            AuthTestResponse response = Objects.requireNonNull(
                    methodsClient.authTest(AuthTestRequest.builder().token(properties.botToken()).build()),
                    "Slack auth.test response must not be null");
            if (!response.isOk() || !StringUtils.hasText(response.getUserId())) {
                throw new IllegalStateException("Slack auth.test did not resolve a bot user identity");
            }
            return response.getUserId();
        } catch (Exception exception) {
            throw new IllegalStateException("Slack bot user identity resolution failed");
        }
    }

    @Bean
    SlackSourceIdentityCodec slackSourceIdentityCodec() {
        return new SlackSourceIdentityCodec();
    }

    @Bean
    SlackMentionNormalizer slackMentionNormalizer(
            String slackBotUserId,
            SlackSourceIdentityCodec identityCodec,
            SlackLifecycleMetrics metrics) {
        return new SlackMentionNormalizer(slackBotUserId, Clock.systemUTC(), identityCodec, metrics);
    }

    @Bean
    SlackAppMentionHandler slackAppMentionHandler(
            SlackMentionNormalizer normalizer,
            SourceAcceptanceApplicationService sourceAcceptance,
            SlackLifecycleMetrics metrics) {
        return new SlackAppMentionHandler(normalizer, sourceAcceptance, metrics);
    }

    @Bean
    @ConditionalOnMissingBean(App.class)
    App slackBoltApp(SlackAgentProperties properties, SlackAppMentionHandler mentionHandler) {
        App app = new App(AppConfig.builder().singleTeamBotToken(properties.botToken()).build());
        app.use(new SlackDeliveryMetadataGuard());
        app.event(AppMentionEvent.class, mentionHandler);
        return app;
    }

    @Bean
    @ConditionalOnMissingBean(SocketModeApp.class)
    SocketModeApp socketModeApp(SlackAgentProperties properties, App slackBoltApp) throws Exception {
        return new SocketModeApp(
                properties.appToken(),
                SocketModeClient.Backend.JavaWebSocket,
                SlackAppMentionHandler.noAckOnFailure(),
                slackBoltApp);
    }

    @Bean
    @ConditionalOnMissingBean(SlackSocketModeManager.class)
    SlackSocketModeManager slackSocketModeManager(SocketModeApp socketModeApp) {
        return new SlackSocketModeManager(socketModeApp);
    }

    @Bean
    SlackChannelRateGate slackChannelRateGate() {
        return new SlackChannelRateGate();
    }

    @Bean
    SlackDeliveryAdapter slackDeliveryAdapter(
            MethodsClient methodsClient,
            SlackAgentProperties properties,
            SlackSourceIdentityCodec identityCodec,
            SlackChannelRateGate rateGate,
            SlackLifecycleMetrics metrics) {
        return new SlackDeliveryAdapter(methodsClient, properties.botToken(), identityCodec, rateGate, metrics);
    }

    @Bean
    DeliveryRetryPolicy deliveryRetryPolicy(AgentWorkerProperties properties) {
        return new DeliveryRetryPolicy(
                Duration.ofSeconds(1), Duration.ofMinutes(5), attempt -> Duration.ZERO, properties.deliveryMaxAttempts());
    }

    @Bean
    DeliveryProcessor deliveryProcessor(
            PostgresDeliveryOutboxAdapter deliveryOutbox,
            SlackDeliveryAdapter deliveryAdapter,
            DeliveryRetryPolicy retryPolicy,
            InboxLifecycleMetrics metrics) {
        return new DeliveryProcessor(deliveryOutbox, deliveryAdapter, retryPolicy, metrics);
    }

    @Bean
    DeliveryWorkApplicationService deliveryWorkApplicationService(
            PostgresDeliveryOutboxAdapter deliveryOutbox,
            DeliveryProcessor processor,
            ClaimAdmissionCoordinator claimAdmission) {
        return new DeliveryWorkApplicationService(deliveryOutbox, processor, claimAdmission);
    }

    @Bean
    StartupRecoveryApplicationService startupRecoveryApplicationService(
            PostgresSessionInboxAdapter inbox,
            PostgresDeliveryOutboxAdapter deliveryOutbox) {
        return new StartupRecoveryApplicationService(inbox, deliveryOutbox);
    }

    @Bean
    @ConditionalOnMissingBean(AgentWorkerManager.class)
    AgentWorkerManager agentWorkerManager(
            RecoverInterruptedWorkUseCase recovery,
            ProcessNextInboxUseCase inboxProcessor,
            ProcessNextDeliveryUseCase deliveryProcessor,
            StopClaimingUseCase claimAdmission,
            AgentWorkerProperties properties) {
        return new AgentWorkerManager(
                recovery,
                inboxProcessor,
                deliveryProcessor,
                properties.inboxPollInterval(),
                properties.deliveryPollInterval(),
                properties.shutdownGracePeriod(),
                claimAdmission);
    }

    @Bean
    @ConditionalOnMissingBean(ReadAgentOperationsUseCase.class)
    AgentOperationsApplicationService agentOperationsApplicationService(
            PostgresAgentOperationsAdapter operationsPort,
            AgentWorkerManager workerManager) {
        return new AgentOperationsApplicationService(operationsPort, workerManager::operationsState);
    }
}
