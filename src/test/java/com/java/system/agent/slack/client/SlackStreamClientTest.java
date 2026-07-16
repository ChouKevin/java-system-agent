package com.java.system.agent.slack.client;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.java.system.agent.slack.model.SlackMessageContext;
import com.slack.api.RequestConfigurator;
import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.methods.MethodsClient;
import okhttp3.FormBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class SlackStreamClientTest {

    private static final String BOT_TOKEN = "xoxb-test";
    private static final String INTERNAL_DETAIL = "quota exceeded for internal-llm-endpoint";

    @Mock
    private App app;

    @Mock
    private AppConfig appConfig;

    @Mock
    private MethodsClient methodsClient;

    @Mock
    private SlackStreamFailureHandler failureHandler;

    @Captor
    private ArgumentCaptor<RequestConfigurator<FormBody.Builder>> formCaptor;

    private SlackStreamClient slackStreamClient;
    private Logger streamLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        slackStreamClient = new SlackStreamClient(app);
        streamLogger = (Logger) LoggerFactory.getLogger(SlackStreamClient.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        streamLogger.addAppender(logAppender);
        when(app.config()).thenReturn(appConfig);
        when(app.client()).thenReturn(methodsClient);
        when(appConfig.getSingleTeamBotToken()).thenReturn(BOT_TOKEN);
    }

    @AfterEach
    void tearDownLogCapture() {
        streamLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    void should_send_generic_error_message_without_internal_detail_when_stream_fails() throws Exception {
        SlackStreamResponse startResponse = new SlackStreamResponse();
        startResponse.setOk(true);
        startResponse.setTs("1720000000.000100");
        when(methodsClient.postFormWithTokenAndParseResponse(
                any(), eq("chat.startStream"), eq(BOT_TOKEN), eq(SlackStreamResponse.class)))
                .thenReturn(startResponse);

        SlackStreamResponse stopResponse = new SlackStreamResponse();
        stopResponse.setOk(true);
        when(methodsClient.postFormWithTokenAndParseResponse(
                any(), eq("chat.stopStream"), eq(BOT_TOKEN), eq(SlackStreamResponse.class)))
                .thenReturn(stopResponse);

        SlackMessageContext ctx = SlackMessageContext.builder()
                .traceId("trace-1")
                .channelId("D0123456789")
                .threadTs("1719999999.000001")
                .eventId("Ev06TEST0001")
                .build();

        slackStreamClient.consumeStream(ctx, "",
                () -> Flux.error(new IllegalStateException(INTERNAL_DETAIL)), failureHandler);

        verify(methodsClient, timeout(5000)).postFormWithTokenAndParseResponse(
                formCaptor.capture(), eq("chat.stopStream"), eq(BOT_TOKEN), eq(SlackStreamResponse.class));

        FormBody formBody = formCaptor.getValue().configure(new FormBody.Builder()).build();
        Optional<String> markdownText = formValue(formBody, "markdown_text");
        assertThat(markdownText).isPresent();
        assertThat(markdownText.get()).doesNotContain(INTERNAL_DETAIL);
        assertThat(markdownText.get()).contains("分析過程中發生錯誤");
    }

    @Test
    void should_notify_failure_handler_and_skip_content_when_start_stream_returns_error() throws Exception {
        stubStartStream(streamResponse(false, "invalid_auth", null));
        AtomicBoolean supplierCalled = new AtomicBoolean(false);
        Supplier<Flux<String>> contentSupplier = () -> {
            supplierCalled.set(true);
            return Flux.empty();
        };

        slackStreamClient.consumeStream(streamContext(), "", contentSupplier, failureHandler);

        ArgumentCaptor<SlackStreamFailure> captor = ArgumentCaptor.forClass(SlackStreamFailure.class);
        verify(failureHandler).handle(captor.capture());
        assertThat(captor.getValue().reason()).contains("invalid_auth");
        assertThat(supplierCalled.get()).isFalse();
    }

    @Test
    void should_notify_failure_handler_when_start_stream_returns_empty_ts() throws Exception {
        stubStartStream(streamResponse(true, null, ""));

        slackStreamClient.consumeStream(streamContext(), "", () -> Flux.empty(), failureHandler);

        ArgumentCaptor<SlackStreamFailure> captor = ArgumentCaptor.forClass(SlackStreamFailure.class);
        verify(failureHandler).handle(captor.capture());
        assertThat(captor.getValue().reason()).contains("empty ts");
    }

    @Test
    void should_notify_failure_handler_when_start_stream_call_throws() throws Exception {
        when(methodsClient.postFormWithTokenAndParseResponse(
                any(), eq("chat.startStream"), eq(BOT_TOKEN), eq(SlackStreamResponse.class)))
                .thenThrow(new IOException("connection reset"));

        slackStreamClient.consumeStream(streamContext(), "", () -> Flux.empty(), failureHandler);

        ArgumentCaptor<SlackStreamFailure> captor = ArgumentCaptor.forClass(SlackStreamFailure.class);
        verify(failureHandler).handle(captor.capture());
        assertThat(captor.getValue().reason()).startsWith("stream start failed");
    }

    @Test
    void should_append_and_stop_without_failure_when_stream_completes_normally() throws Exception {
        stubStartStream(streamResponse(true, null, "123.456"));

        slackStreamClient.consumeStream(streamContext(), "", () -> Flux.just("hello"), failureHandler);

        verify(methodsClient, timeout(5000)).postFormWithTokenAndParseResponse(
                any(), eq("chat.appendStream"), eq(BOT_TOKEN), eq(SlackStreamResponse.class));
        verify(methodsClient, timeout(5000)).postFormWithTokenAndParseResponse(
                any(), eq("chat.stopStream"), eq(BOT_TOKEN), eq(SlackStreamResponse.class));
        verify(failureHandler, never()).handle(any());
    }

    @Test
    void should_correlate_append_failure_without_exposing_stream_content() throws Exception {
        stubStartStream(streamResponse(true, null, "123.456"));
        when(methodsClient.postFormWithTokenAndParseResponse(
                any(), eq("chat.appendStream"), eq(BOT_TOKEN), eq(SlackStreamResponse.class)))
                .thenReturn(streamResponse(false, "invalid_blocks", null));

        slackStreamClient.consumeStream(
                streamContext(), "", () -> Flux.just("private analysis content"), failureHandler);

        verify(methodsClient, timeout(5000)).postFormWithTokenAndParseResponse(
                any(), eq("chat.stopStream"), eq(BOT_TOKEN), eq(SlackStreamResponse.class));
        assertThat(logAppender.list)
                .anySatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains("chat.appendStream failed")
                        .contains("trace-1")
                        .contains("Ev123")
                        .contains("111.222")
                        .doesNotContain("private analysis content"));
    }

    @Test
    void should_correlate_stop_exception_and_preserve_original_stream_failure() throws Exception {
        stubStartStream(streamResponse(true, null, "123.456"));
        when(methodsClient.postFormWithTokenAndParseResponse(
                any(), eq("chat.stopStream"), eq(BOT_TOKEN), eq(SlackStreamResponse.class)))
                .thenThrow(new IOException("connection reset"));
        IllegalStateException streamFailure = new IllegalStateException("LLM connection lost");

        slackStreamClient.consumeStream(
                streamContext(), "", () -> Flux.error(streamFailure), failureHandler);

        ArgumentCaptor<SlackStreamFailure> captor = ArgumentCaptor.forClass(SlackStreamFailure.class);
        verify(failureHandler, timeout(5000)).handle(captor.capture());
        assertThat(captor.getValue().cause()).isSameAs(streamFailure);
        assertThat(logAppender.list)
                .anySatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains("Error calling chat.stopStream")
                        .contains("trace-1")
                        .contains("Ev123")
                        .contains("111.222"));
    }

    @Test
    void should_notify_failure_handler_and_stop_stream_when_stream_errors_mid_flight() throws Exception {
        stubStartStream(streamResponse(true, null, "123.456"));
        IllegalStateException boom = new IllegalStateException("LLM connection lost");

        slackStreamClient.consumeStream(streamContext(), "", () -> Flux.error(boom), failureHandler);

        ArgumentCaptor<SlackStreamFailure> captor = ArgumentCaptor.forClass(SlackStreamFailure.class);
        verify(failureHandler, timeout(5000)).handle(captor.capture());
        assertThat(captor.getValue().cause()).isSameAs(boom);
        verify(methodsClient, timeout(5000)).postFormWithTokenAndParseResponse(
                any(), eq("chat.stopStream"), eq(BOT_TOKEN), eq(SlackStreamResponse.class));
    }

    private void stubStartStream(SlackStreamResponse response) throws Exception {
        when(methodsClient.postFormWithTokenAndParseResponse(
                any(), eq("chat.startStream"), eq(BOT_TOKEN), eq(SlackStreamResponse.class)))
                .thenReturn(response);
    }

    private SlackStreamResponse streamResponse(boolean ok, String error, String ts) {
        SlackStreamResponse response = new SlackStreamResponse();
        response.setOk(ok);
        response.setError(error);
        response.setTs(ts);
        return response;
    }

    private SlackMessageContext streamContext() {
        return SlackMessageContext.builder()
                .traceId("trace-1")
                .userId("U123")
                .teamId("T123")
                .channelId("C123")
                .eventId("Ev123")
                .threadTs("111.222")
                .build();
    }

    private static Optional<String> formValue(FormBody formBody, String name) {
        for (int i = 0; i < formBody.size(); i++) {
            if (name.equals(formBody.name(i))) {
                return Optional.of(formBody.value(i));
            }
        }
        return Optional.empty();
    }
}
