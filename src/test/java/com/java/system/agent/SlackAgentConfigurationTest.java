package com.java.system.agent;

import com.java.system.agent.slack.source.SlackAppMentionHandler;
import com.slack.api.bolt.App;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.auth.AuthTestRequest;
import com.slack.api.methods.response.auth.AuthTestResponse;
import com.slack.api.socket_mode.SocketModeClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Slack privileged bootstrap 的 bot identity fallback 測試
 */
class SlackAgentConfigurationTest {

    @Test
    void resolvesTheBotIdentityThroughAuthTestWhenTheOptionalPropertyIsBlank() throws Exception {
        MethodsClient methodsClient = mock(MethodsClient.class);
        AuthTestResponse response = new AuthTestResponse();
        response.setOk(true);
        response.setUserId("U_RESOLVED");
        when(methodsClient.authTest(any(AuthTestRequest.class))).thenReturn(response);

        String botUserId = new SlackAgentConfiguration().slackBotUserId(
                new SlackAgentProperties("xapp-test", "xoxb-test", ""), methodsClient);

        assertThat(botUserId).isEqualTo("U_RESOLVED");
    }

    @Test
    void sanitizesAuthTestFailuresWithoutRetainingTheProviderException() throws Exception {
        MethodsClient methodsClient = mock(MethodsClient.class);
        when(methodsClient.authTest(any(AuthTestRequest.class)))
                .thenThrow(new IllegalStateException("xoxb-raw-token provider response"));

        assertThatThrownBy(() -> new SlackAgentConfiguration().slackBotUserId(
                new SlackAgentProperties("xapp-test", "xoxb-test", ""), methodsClient))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Slack bot user identity resolution failed")
                .hasNoCause()
                .satisfies(exception -> assertThat(exception.toString()).doesNotContain("xoxb-raw-token"));
    }

    @Test
    void configuresSocketModeWithTheJavaWebSocketBackendAndNoAckFailureHandler() throws Exception {
        App boltApp = mock(App.class);

        SocketModeApp socketModeApp = new SlackAgentConfiguration().socketModeApp(
                new SlackAgentProperties("xapp-test", "xoxb-test", "U_BOT"), boltApp);

        assertThat(configuredBackend(socketModeApp)).isEqualTo(SocketModeClient.Backend.JavaWebSocket);
        assertThat(configuredErrorHandler(socketModeApp).getClass().getName())
                .contains(SlackAppMentionHandler.class.getName());
    }

    @SuppressWarnings("unchecked")
    private static Function<SocketModeApp.ErrorContext, ?> configuredErrorHandler(SocketModeApp socketModeApp) throws Exception {
        Object clientFactory = clientFactory(socketModeApp);
        Field functionField = Arrays.stream(clientFactory.getClass().getDeclaredFields())
                .filter(field -> Function.class.isAssignableFrom(field.getType()))
                .findFirst()
                .orElseThrow();
        functionField.setAccessible(true);
        return (Function<SocketModeApp.ErrorContext, ?>) functionField.get(clientFactory);
    }

    private static SocketModeClient.Backend configuredBackend(SocketModeApp socketModeApp) throws Exception {
        Object clientFactory = clientFactory(socketModeApp);
        Field backendField = Arrays.stream(clientFactory.getClass().getDeclaredFields())
                .filter(field -> field.getType().equals(SocketModeClient.Backend.class))
                .findFirst()
                .orElseThrow();
        backendField.setAccessible(true);
        return (SocketModeClient.Backend) backendField.get(clientFactory);
    }

    private static Object clientFactory(SocketModeApp socketModeApp) throws Exception {
        Field clientFactoryField = SocketModeApp.class.getDeclaredField("clientFactory");
        clientFactoryField.setAccessible(true);
        return clientFactoryField.get(socketModeApp);
    }
}
