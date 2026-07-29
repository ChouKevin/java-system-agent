package com.java.system.agent.slack.source;

import com.java.system.agent.interaction.domain.SourceAcceptance;
import com.java.system.agent.interaction.domain.SourceAcceptanceStatus;
import com.java.system.agent.interaction.domain.SourceAdmission;
import com.java.system.agent.interaction.domain.InboxMessageId;
import com.java.system.agent.interaction.port.in.AcceptSourceEventUseCase;
import com.java.system.agent.answering.domain.conversation.SessionId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.response.Response;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.socket_mode.SocketModeClient;
import com.slack.api.socket_mode.response.SocketModeResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Socket Mode durable-before-ACK 的 SDK dispatch 契約測試
 */
class SlackSocketModeAcknowledgementTest {

    @Test
    void doesNotAcknowledgeWhenDurableAcceptanceFails() throws Exception {
        AcceptSourceEventUseCase acceptance = mock(AcceptSourceEventUseCase.class);
        when(acceptance.accept(any())).thenThrow(new IllegalStateException("database unavailable"));
        SocketModeClient socketModeClient = mock(SocketModeClient.class);

        invokePinnedRunBoltApp(configuredApp(acceptance), socketModeClient);

        verify(socketModeClient, never()).sendSocketModeResponse(anyString());
        verify(socketModeClient, never()).sendSocketModeResponse(any(SocketModeResponse.class));
    }

    @Test
    void acknowledgesExactlyOnceAfterDurableAcceptanceReturns() throws Exception {
        AcceptSourceEventUseCase acceptance = mock(AcceptSourceEventUseCase.class);
        SocketModeClient socketModeClient = mock(SocketModeClient.class);
        boolean[] returned = {false};
        doAnswer(invocation -> {
            returned[0] = true;
            return accepted();
        }).when(acceptance).accept(any());
        doAnswer(invocation -> {
            org.junit.jupiter.api.Assertions.assertTrue(returned[0]);
            return null; // cs-allow
        }).when(socketModeClient).sendSocketModeResponse(any(SocketModeResponse.class));

        invokePinnedRunBoltApp(configuredApp(acceptance), socketModeClient);

        org.mockito.ArgumentCaptor<SocketModeResponse> acknowledgement = forClass(SocketModeResponse.class);
        verify(socketModeClient, times(1)).sendSocketModeResponse(acknowledgement.capture());
        verify(socketModeClient, never()).sendSocketModeResponse(anyString());
        org.junit.jupiter.api.Assertions.assertEquals("envelope-1", acknowledgement.getValue().getEnvelopeId());
        org.junit.jupiter.api.Assertions.assertTrue(returned[0]);
    }

    @Test
    void doesNotAcknowledgeWhenDurableAcceptanceReachesTheThreeSecondBudget() throws Exception {
        AcceptSourceEventUseCase acceptance = mock(AcceptSourceEventUseCase.class);
        SocketModeClient socketModeClient = mock(SocketModeClient.class);

        invokePinnedRunBoltApp(configuredApp(acceptance, nanos(0, java.time.Duration.ofSeconds(3).toNanos())),
                socketModeClient);

        verify(socketModeClient, never()).sendSocketModeResponse(anyString());
        verify(socketModeClient, never()).sendSocketModeResponse(any(SocketModeResponse.class));
    }

    private static App configuredApp(AcceptSourceEventUseCase acceptance) {
        return configuredApp(acceptance, System::nanoTime);
    }

    private static App configuredApp(AcceptSourceEventUseCase acceptance, LongSupplier nanos) {
        App app = new App(AppConfig.builder().singleTeamBotToken(null).build()); // cs-allow
        app.use(new SlackDeliveryMetadataGuard());
        app.event(com.slack.api.model.event.AppMentionEvent.class, new SlackAppMentionHandler(
                new SlackMentionNormalizer("UBOT", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), new SlackSourceIdentityCodec()),
                acceptance, com.java.system.agent.slack.SlackLifecycleMetrics.NO_OP, nanos));
        return app;
    }

    private static LongSupplier nanos(long... values) {
        AtomicInteger index = new AtomicInteger();
        return () -> values[Math.min(index.getAndIncrement(), values.length - 1)];
    }

    private static void invokePinnedRunBoltApp(App app, SocketModeClient client) throws Exception {
        Method method = SocketModeApp.class.getDeclaredMethod("runBoltApp", String.class, App.class,
                SocketModeClient.class, Class.forName("com.slack.api.bolt.socket_mode.request.SocketModeRequestParser"),
                java.util.function.Function.class, com.google.gson.Gson.class);
        method.setAccessible(true);
        Object parser = Class.forName("com.slack.api.bolt.socket_mode.request.SocketModeRequestParser")
                .getConstructor(AppConfig.class).newInstance(app.config());
        String envelope = """
                {"envelope_id":"envelope-1","type":"events_api","payload":{"type":"event_callback","event_id":"Ev1","event":{"type":"app_mention","team":"T1","channel":"C1","user":"U1","text":"<@UBOT> question","ts":"1.1"}}}
                """;
        method.invoke(null, envelope, app, client, parser, SlackAppMentionHandler.noAckOnFailure(), new com.google.gson.Gson());
    }

    private static SourceAcceptance accepted() {
        return new SourceAcceptance(SourceAcceptanceStatus.ACCEPTED,
                Optional.of(new SourceAdmission(new InboxMessageId("inbox-1"), new SessionId("session-1"), 0,
                        new AnalysisRunId("run-1"))), Optional.empty());
    }
}
