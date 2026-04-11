package com.java.system.agent.slack.client;

import com.slack.api.RequestConfigurator;
import com.slack.api.bolt.App;
import com.slack.api.methods.SlackApiException;
import com.java.system.agent.slack.model.SlackMessageContext;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.util.function.Supplier;

import reactor.core.scheduler.Schedulers;

/** 封裝 Slack Streaming API（startStream / appendStream / stopStream） */
@Component
@Slf4j
public class SlackStreamClient {

    private final App app;

    public SlackStreamClient(App app) {
        this.app = app;
    }

    /** 開始串流並消費 contentSupplier 的 Flux 逐批 append */
    public void consumeStream(SlackMessageContext ctx, String initialMarkdownText,
            Supplier<Flux<String>> contentSupplier) {
        SlackStreamResponse startResponse = startStream(ctx, initialMarkdownText);
        if (ObjectUtils.isEmpty(startResponse) || !startResponse.isOk()) {
            log.error("Failed to start Slack stream: {}", ObjectUtils.isEmpty(startResponse)
                    ? "null response"
                    : startResponse.getError());
            return;
        }
        String streamTs = startResponse.getTs();
        if (!StringUtils.hasText(streamTs)) {
            log.error("chat.startStream returned empty ts (eventId: {})", ctx.getEventId());
            return;
        }
        consumeStreamInternal(ctx, streamTs, contentSupplier.get());
    }

    /** 1 秒緩衝視窗，每批累積後 append 一次 */
    private void consumeStreamInternal(SlackMessageContext ctx, String streamTs, Flux<String> content) {
        content.doOnNext(item -> log.debug("consumeStream: raw item len={}", item.length()))
                .bufferTimeout(Integer.MAX_VALUE, Duration.ofMillis(1000))
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunks -> {
                    String delta = String.join("", chunks);
                    log.debug("consumeStream: flushing batch size={}, deltaLen={}", chunks.size(), delta.length());
                    if (StringUtils.hasText(delta)) {
                        appendStream(ctx.getChannelId(), streamTs, delta);
                    }
                })
                .doOnComplete(() -> {
                    stopStream(ctx.getChannelId(), streamTs, null);
                    log.info("Stream completed for event {}", ctx.getEventId());
                })
                .doOnError(e -> {
                    log.error("Error during streaming", e);
                    stopStream(ctx.getChannelId(), streamTs, "\n\n❌ **分析過程中發生錯誤**: " + e.getMessage());
                })
                .subscribe();
    }

    private SlackStreamResponse startStream(SlackMessageContext ctx, String initialMarkdownText) {
        String token = app.config().getSingleTeamBotToken();
        if (!StringUtils.hasText(token)) {
            log.error("Missing Slack bot token for chat.startStream");
            return null;
        }
        if (!StringUtils.hasText(ctx.getThreadTs())) {
            log.error("Missing thread_ts for chat.startStream (eventId: {})", ctx.getEventId());
            return null;
        }
        boolean needsRecipient = StringUtils.hasText(ctx.getChannelId())
                && (ctx.getChannelId().startsWith("C") || ctx.getChannelId().startsWith("G"));
        if (needsRecipient && (!StringUtils.hasText(ctx.getUserId())
                || !StringUtils.hasText(ctx.getTeamId()))) {
            log.error("Missing recipient_user_id/recipient_team_id for chat.startStream (channel: {}, eventId: {})",
                    ctx.getChannelId(), ctx.getEventId());
            return null;
        }
        try {
            RequestConfigurator<FormBody.Builder> form = builder -> {
                builder.add("channel", ctx.getChannelId());
                builder.add("thread_ts", ctx.getThreadTs());
                if (StringUtils.hasText(initialMarkdownText)) {
                    builder.add("markdown_text", initialMarkdownText);
                }
                if (StringUtils.hasText(ctx.getUserId())) {
                    builder.add("recipient_user_id", ctx.getUserId());
                }
                if (StringUtils.hasText(ctx.getTeamId())) {
                    builder.add("recipient_team_id", ctx.getTeamId());
                }
                return builder;
            };
            return app.client().postFormWithTokenAndParseResponse(
                    form,
                    "chat.startStream",
                    token,
                    SlackStreamResponse.class);
        } catch (IOException | SlackApiException e) {
            log.error("Error calling chat.startStream", e);
            return null;
        }
    }

    private void appendStream(String channelId, String streamTs, String markdownText) {
        String token = app.config().getSingleTeamBotToken();
        if (!StringUtils.hasText(token)) {
            log.error("Missing Slack bot token for chat.appendStream");
            return;
        }
        try {
            RequestConfigurator<FormBody.Builder> form = builder -> builder
                    .add("channel", channelId)
                    .add("ts", streamTs)
                    .add("markdown_text", markdownText);
            SlackStreamResponse response = app.client().postFormWithTokenAndParseResponse(
                    form,
                    "chat.appendStream",
                    token,
                    SlackStreamResponse.class);
            if (ObjectUtils.isEmpty(response) || !response.isOk()) {
                log.warn("chat.appendStream failed: {}", ObjectUtils.isEmpty(response)
                        ? "null response"
                        : response.getError());
            }
        } catch (IOException | SlackApiException e) {
            log.warn("Error calling chat.appendStream", e);
        }
    }

    private void stopStream(String channelId, String streamTs, String finalMarkdownText) {
        String token = app.config().getSingleTeamBotToken();
        if (!StringUtils.hasText(token)) {
            log.error("Missing Slack bot token for chat.stopStream");
            return;
        }
        try {
            RequestConfigurator<FormBody.Builder> form = builder -> {
                builder.add("channel", channelId);
                builder.add("ts", streamTs);
                if (StringUtils.hasText(finalMarkdownText)) {
                    builder.add("markdown_text", finalMarkdownText);
                }
                return builder;
            };
            SlackStreamResponse response = app.client().postFormWithTokenAndParseResponse(
                    form,
                    "chat.stopStream",
                    token,
                    SlackStreamResponse.class);
            if (ObjectUtils.isEmpty(response) || !response.isOk()) {
                log.warn("chat.stopStream failed: {}", ObjectUtils.isEmpty(response)
                        ? "null response"
                        : response.getError());
            }
        } catch (IOException | SlackApiException e) {
            log.warn("Error calling chat.stopStream", e);
        }
    }
}
