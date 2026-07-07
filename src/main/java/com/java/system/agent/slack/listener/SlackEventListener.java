package com.java.system.agent.slack.listener;

import com.slack.api.bolt.App;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.model.event.AppMentionEvent;
import com.java.system.agent.ratelimit.RateLimit;
import com.java.system.agent.ratelimit.RateLimitExceededException;
import com.java.system.agent.ratelimit.RateLimitingService;
import com.java.system.agent.slack.model.SlackMessageContext;
import com.java.system.agent.slack.pipeline.SlackAgentPipeline;
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

        // app_mentions:read、chat:write
        app.event(AppMentionEvent.class, (req, ctx) -> {
            String eventId = req.getEventId();

            if (slackEventDeduplicator.isDuplicate(eventId)) {
                log.info("Skipping duplicate eventId: {}", eventId);
                return ctx.ack();
            }

            log.info("Received new eventId: {}", eventId);
            AppMentionEvent event = req.getEvent();
            String threadTs = StringUtils.hasText(event.getThreadTs()) ? event.getThreadTs() : event.getTs();
            SlackMessageContext slackCtx = SlackMessageContext.builder()
                    .userId(event.getUser())
                    .teamId(event.getTeam())
                    .channelId(event.getChannel())
                    .text(event.getText())
                    .eventId(eventId)
                    .threadTs(threadTs)
                    .build();
            try {
                self.processAppMention(slackCtx);
            } catch (RateLimitExceededException e) {
                log.warn("Rate limit exceeded for user {} (eventId: {})", slackCtx.getUserId(), eventId);
                try {
                    app.client().chatPostMessage(r -> r.channel(slackCtx.getChannelId())
                            .text(slackCtx.getRateLimitMessage(rateLimitingService.getCooldownSeconds())));
                } catch (Exception ex) {
                    log.error("Failed to send rate limit message to Slack", ex);
                }
            }
            return ctx.ack();
        });

        socketModeApp = new SocketModeApp(appToken, app);
        socketModeApp.startAsync();
        log.info("Slack Socket Mode App started.");
    }

    @Async
    @RateLimit(message = "Rate limit exceeded")
    public void processAppMention(SlackMessageContext ctx) {
        try {
            log.info("Processing async AI request for user {} in channel {} (eventId: {})",
                    ctx.getUserId(), ctx.getChannelId(), ctx.getEventId());

            routeToPipeline(ctx);
        } catch (Exception e) {
            log.error("Failed to process Slack message (eventId: {})", ctx.getEventId(), e);
        }
    }

    void routeToPipeline(SlackMessageContext ctx) {
        agentPipeline.execute(ctx);
    }

    @PreDestroy
    public void stop() throws Exception {
        if (!ObjectUtils.isEmpty(socketModeApp)) {
            socketModeApp.stop();
        }
    }
}
