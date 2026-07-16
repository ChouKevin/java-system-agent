package com.java.system.agent.slack.client;

import com.slack.api.RequestConfigurator;
import com.slack.api.bolt.App;
import com.slack.api.methods.SlackApiException;
import com.java.system.agent.slack.model.SlackMessageContext;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
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

    /** 串流失敗時回覆給使用者的固定訊息，避免把內部例外細節洩漏到 Slack 頻道 */
    private static final String STREAM_ERROR_MESSAGE = "\n\n❌ **分析過程中發生錯誤**，請稍後再試";

    private final App app;

    public SlackStreamClient(App app) {
        this.app = app;
    }

    /** 開始串流並消費 contentSupplier 的 Flux 逐批 append，失敗時通知呼叫端善後 */
    public void consumeStream(SlackMessageContext ctx, String initialMarkdownText,
            Supplier<Flux<String>> contentSupplier, SlackStreamFailureHandler failureHandler) {
        Assert.notNull(failureHandler, "failureHandler must not be null");
        SlackStreamResponse startResponse = startStream(ctx, initialMarkdownText);
        if (ObjectUtils.isEmpty(startResponse) || !startResponse.isOk()) {
            String reason = ObjectUtils.isEmpty(startResponse) ? "null response" : startResponse.getError();
            log.error("Failed to start Slack stream (traceId: {}, eventId: {}, threadTs: {}): {}",
                    ctx.getTraceId(), ctx.getEventId(), ctx.getThreadTs(), reason);
            failureHandler.handle(SlackStreamFailure.startFailure(reason));
            return;
        }
        String streamTs = startResponse.getTs();
        if (!StringUtils.hasText(streamTs)) {
            log.error("chat.startStream returned empty ts (traceId: {}, eventId: {}, threadTs: {})",
                    ctx.getTraceId(), ctx.getEventId(), ctx.getThreadTs());
            failureHandler.handle(SlackStreamFailure.startFailure("chat.startStream returned empty ts"));
            return;
        }
        log.info("Slack stream started (traceId: {}, eventId: {}, threadTs: {})",
                ctx.getTraceId(), ctx.getEventId(), ctx.getThreadTs());
        consumeStreamInternal(ctx, streamTs, contentSupplier.get(), failureHandler);
    }

    /** 1 秒緩衝視窗，每批累積後 append 一次，終止錯誤交回呼叫端善後 */
    private void consumeStreamInternal(SlackMessageContext ctx, String streamTs, Flux<String> content,
            SlackStreamFailureHandler failureHandler) {
        content.doOnNext(item -> log.debug("consumeStream: raw item len={}", item.length()))
                .bufferTimeout(Integer.MAX_VALUE, Duration.ofMillis(1000))
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunks -> {
                    String delta = String.join("", chunks);
                    log.debug("consumeStream: flushing batch size={}, deltaLen={}", chunks.size(), delta.length());
                    if (StringUtils.hasText(delta)) {
                        appendStream(ctx, streamTs, delta);
                    }
                })
                .doOnComplete(() -> {
                    stopStream(ctx, streamTs, null);
                    log.info("Slack stream completed (traceId: {}, eventId: {}, threadTs: {})",
                            ctx.getTraceId(), ctx.getEventId(), ctx.getThreadTs());
                })
                .doOnError(e -> {
                    log.error("Error during Slack streaming "
                                    + "(traceId: {}, eventId: {}, threadTs: {}, channel: {})",
                            ctx.getTraceId(), ctx.getEventId(), ctx.getThreadTs(), ctx.getChannelId(), e);
                    stopStream(ctx, streamTs, STREAM_ERROR_MESSAGE);
                })
                .subscribe(
                        ignored -> { },
                        error -> failureHandler.handle(SlackStreamFailure.streamingFailure(error)));
    }

    private SlackStreamResponse startStream(SlackMessageContext ctx, String initialMarkdownText) {
        String token = app.config().getSingleTeamBotToken();
        if (!StringUtils.hasText(token)) {
            log.error("Missing Slack bot token for chat.startStream "
                            + "(traceId: {}, eventId: {}, threadTs: {})",
                    ctx.getTraceId(), ctx.getEventId(), ctx.getThreadTs());
            return null;
        }
        if (!StringUtils.hasText(ctx.getThreadTs())) {
            log.error("Missing thread_ts for chat.startStream (traceId: {}, eventId: {}, threadTs: {})",
                    ctx.getTraceId(), ctx.getEventId(), ctx.getThreadTs());
            return null;
        }
        boolean needsRecipient = StringUtils.hasText(ctx.getChannelId())
                && (ctx.getChannelId().startsWith("C") || ctx.getChannelId().startsWith("G"));
        if (needsRecipient && (!StringUtils.hasText(ctx.getUserId())
                || !StringUtils.hasText(ctx.getTeamId()))) {
            log.error("Missing recipient_user_id/recipient_team_id for chat.startStream "
                            + "(traceId: {}, eventId: {}, threadTs: {}, channel: {})",
                    ctx.getTraceId(), ctx.getEventId(), ctx.getThreadTs(), ctx.getChannelId());
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
            log.error("Error calling chat.startStream (traceId: {}, eventId: {}, threadTs: {})",
                    ctx.getTraceId(), ctx.getEventId(), ctx.getThreadTs(), e);
            return null;
        }
    }

    private void appendStream(SlackMessageContext ctx, String streamTs, String markdownText) {
        String token = app.config().getSingleTeamBotToken();
        if (!StringUtils.hasText(token)) {
            log.error("Missing Slack bot token for chat.appendStream "
                            + "(traceId: {}, eventId: {}, threadTs: {})",
                    ctx.getTraceId(), ctx.getEventId(), ctx.getThreadTs());
            return;
        }
        try {
            RequestConfigurator<FormBody.Builder> form = builder -> builder
                    .add("channel", ctx.getChannelId())
                    .add("ts", streamTs)
                    .add("markdown_text", markdownText);
            SlackStreamResponse response = app.client().postFormWithTokenAndParseResponse(
                    form,
                    "chat.appendStream",
                    token,
                    SlackStreamResponse.class);
            if (ObjectUtils.isEmpty(response) || !response.isOk()) {
                log.warn("chat.appendStream failed (traceId: {}, eventId: {}, threadTs: {}): {}",
                        ctx.getTraceId(), ctx.getEventId(), ctx.getThreadTs(),
                        ObjectUtils.isEmpty(response) ? "null response" : response.getError());
            }
        } catch (IOException | SlackApiException e) {
            log.warn("Error calling chat.appendStream (traceId: {}, eventId: {}, threadTs: {})",
                    ctx.getTraceId(), ctx.getEventId(), ctx.getThreadTs(), e);
        }
    }

    private void stopStream(SlackMessageContext ctx, String streamTs, String finalMarkdownText) {
        String token = app.config().getSingleTeamBotToken();
        if (!StringUtils.hasText(token)) {
            log.error("Missing Slack bot token for chat.stopStream "
                            + "(traceId: {}, eventId: {}, threadTs: {})",
                    ctx.getTraceId(), ctx.getEventId(), ctx.getThreadTs());
            return;
        }
        try {
            RequestConfigurator<FormBody.Builder> form = builder -> {
                builder.add("channel", ctx.getChannelId());
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
                log.warn("chat.stopStream failed (traceId: {}, eventId: {}, threadTs: {}): {}",
                        ctx.getTraceId(), ctx.getEventId(), ctx.getThreadTs(),
                        ObjectUtils.isEmpty(response) ? "null response" : response.getError());
            }
        } catch (IOException | SlackApiException e) {
            log.warn("Error calling chat.stopStream (traceId: {}, eventId: {}, threadTs: {})",
                    ctx.getTraceId(), ctx.getEventId(), ctx.getThreadTs(), e);
        }
    }
}
