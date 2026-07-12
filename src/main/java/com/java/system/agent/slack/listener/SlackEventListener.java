package com.java.system.agent.slack.listener;

import com.java.system.agent.ratelimit.RateLimitingService;
import com.java.system.agent.slack.client.SlackStreamFailure;
import com.java.system.agent.slack.model.SlackMessageContext;
import com.java.system.agent.slack.pipeline.SlackAgentPipeline;
import com.slack.api.bolt.App;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.model.event.AppMentionEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

@Component
@Slf4j
public class SlackEventListener {

    private final App app;
    private final SlackAgentPipeline agentPipeline;
    private final SlackEventDeduplicator slackEventDeduplicator;
    private final RateLimitingService rateLimitingService;
    private final SlackEventListener self;
    private SocketModeApp socketModeApp;

    @Value("${slack.app-token}")
    private String appToken;

    public SlackEventListener(App app,
            SlackAgentPipeline agentPipeline,
            SlackEventDeduplicator slackEventDeduplicator,
            RateLimitingService rateLimitingService,
            @Lazy SlackEventListener self) {
        this.app = app;
        this.agentPipeline = agentPipeline;
        this.slackEventDeduplicator = slackEventDeduplicator;
        this.rateLimitingService = rateLimitingService;
        this.self = self;
    }

    @PostConstruct
    public void start() throws Exception {
        if (!StringUtils.hasText(appToken)) {
            log.warn("Slack app token is not configured. Socket Mode listener is disabled.");
            return;
        }

        app.event(AppMentionEvent.class, (req, ctx) -> {
            AppMentionEvent event = req.getEvent();
            String threadTs = StringUtils.hasText(event.getThreadTs()) ? event.getThreadTs() : event.getTs();
            SlackMessageContext slackCtx = SlackMessageContext.builder()
                    .userId(event.getUser())
                    .teamId(event.getTeam())
                    .channelId(event.getChannel())
                    .text(event.getText())
                    .eventId(req.getEventId())
                    .threadTs(threadTs)
                    .build();
            handleAppMention(slackCtx);
            return ctx.ack();
        });

        socketModeApp = new SocketModeApp(appToken, app);
        socketModeApp.startAsync();
        log.info("Slack Socket Mode App started.");
    }

    /**
     * 同步事件處理入口，先檢查頻率限制，再取得去重處理權，最後轉交非同步管線
     * 被限流的事件不會佔住 eventId，冷卻訊息也能在同步路徑立即回覆
     */
    void handleAppMention(SlackMessageContext slackCtx) {
        if (!rateLimitingService.tryAcquire(slackCtx.getUserId())) {
            log.warn("Rate limit exceeded for user {} (eventId: {})",
                    slackCtx.getUserId(), slackCtx.getEventId());
            postRateLimitMessage(slackCtx);
            return;
        }

        if (!slackEventDeduplicator.tryBegin(slackCtx.getEventId())) {
            log.info("Skipping duplicate eventId: {}", slackCtx.getEventId());
            return;
        }

        log.info("Received new eventId: {}", slackCtx.getEventId());
        self.processAppMention(slackCtx);
    }

    /** 同步回覆冷卻訊息，發送失敗僅記錄 log 不往外拋 */
    private void postRateLimitMessage(SlackMessageContext slackCtx) {
        try {
            app.client().chatPostMessage(r -> r.channel(slackCtx.getChannelId())
                    .text(slackCtx.getRateLimitMessage(rateLimitingService.getCooldownSeconds())));
        } catch (Exception ex) {
            log.error("Failed to send rate limit message to Slack", ex);
        }
    }

    @Async
    public void processAppMention(SlackMessageContext ctx) {
        try {
            log.info("Processing async AI request for user {} in channel {} (eventId: {})",
                    ctx.getUserId(), ctx.getChannelId(), ctx.getEventId());

            routeToPipeline(ctx);
        } catch (Exception e) {
            log.error("Failed to process Slack message (eventId: {})", ctx.getEventId(), e);
            slackEventDeduplicator.abandon(ctx.getEventId());
        }
    }

    void routeToPipeline(SlackMessageContext ctx) {
        agentPipeline.execute(ctx, failure -> handleStreamFailure(ctx, failure));
    }

    /** 串流失敗時釋放 dedup claim，並以一般訊息通知使用者 */
    void handleStreamFailure(SlackMessageContext ctx, SlackStreamFailure failure) {
        log.error("Slack stream failed (eventId: {}): {}", ctx.getEventId(), failure.reason(), failure.cause());
        slackEventDeduplicator.abandon(ctx.getEventId());
        try {
            app.client().chatPostMessage(r -> r.channel(ctx.getChannelId())
                    .threadTs(ctx.getThreadTs())
                    .text(ctx.getStreamFailureMessage()));
        } catch (Exception e) {
            log.error("Failed to send stream-failure fallback message (eventId: {})", ctx.getEventId(), e);
        }
    }

    @PreDestroy
    public void stop() throws Exception {
        if (!ObjectUtils.isEmpty(socketModeApp)) {
            socketModeApp.stop();
        }
    }
}
