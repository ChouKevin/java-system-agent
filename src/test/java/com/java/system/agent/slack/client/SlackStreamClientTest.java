package com.java.system.agent.slack.client;

import com.java.system.agent.slack.model.SlackMessageContext;
import com.slack.api.RequestConfigurator;
import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.methods.MethodsClient;
import okhttp3.FormBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Captor
    private ArgumentCaptor<RequestConfigurator<FormBody.Builder>> formCaptor;

    private SlackStreamClient slackStreamClient;

    @BeforeEach
    void setUp() {
        slackStreamClient = new SlackStreamClient(app);
        when(app.config()).thenReturn(appConfig);
        when(app.client()).thenReturn(methodsClient);
        when(appConfig.getSingleTeamBotToken()).thenReturn(BOT_TOKEN);
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
                .channelId("D0123456789")
                .threadTs("1719999999.000001")
                .eventId("Ev06TEST0001")
                .build();

        slackStreamClient.consumeStream(ctx, "",
                () -> Flux.error(new IllegalStateException(INTERNAL_DETAIL)));

        verify(methodsClient, timeout(5000)).postFormWithTokenAndParseResponse(
                formCaptor.capture(), eq("chat.stopStream"), eq(BOT_TOKEN), eq(SlackStreamResponse.class));

        FormBody formBody = formCaptor.getValue().configure(new FormBody.Builder()).build();
        Optional<String> markdownText = formValue(formBody, "markdown_text");
        assertThat(markdownText).isPresent();
        assertThat(markdownText.get()).doesNotContain(INTERNAL_DETAIL);
        assertThat(markdownText.get()).contains("分析過程中發生錯誤");
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
