package com.java.system.agent.slack.listener;

import com.java.system.agent.ratelimit.RateLimitingService;
import com.java.system.agent.slack.model.SlackMessageContext;
import com.java.system.agent.slack.pipeline.SlackAgentPipeline;
import com.slack.api.RequestConfigurator;
import com.slack.api.bolt.App;
import com.slack.api.methods.MethodsClient;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SlackEventListenerTest {

    @Test
    void should_abandon_dedup_claim_when_pipeline_fails() {
        SlackAgentPipeline pipeline = mock(SlackAgentPipeline.class);
        SlackEventDeduplicator deduplicator = mock(SlackEventDeduplicator.class);
        SlackMessageContext ctx = SlackMessageContext.builder()
                .eventId("event-123")
                .build();
        doThrow(new IllegalStateException("boom")).when(pipeline).execute(ctx);
        SlackEventListener listener = new SlackEventListener(null, pipeline, deduplicator, null, null);

        listener.processAppMention(ctx);

        verify(deduplicator).abandon("event-123");
    }

    @Test
    void should_keep_dedup_claim_when_pipeline_succeeds() {
        SlackAgentPipeline pipeline = mock(SlackAgentPipeline.class);
        SlackEventDeduplicator deduplicator = mock(SlackEventDeduplicator.class);
        SlackMessageContext ctx = SlackMessageContext.builder()
                .eventId("event-123")
                .build();
        SlackEventListener listener = new SlackEventListener(null, pipeline, deduplicator, null, null);

        listener.processAppMention(ctx);

        verify(deduplicator, never()).abandon("event-123");
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_post_cooldown_without_claiming_event_when_rate_limited() throws Exception {
        App app = mock(App.class);
        MethodsClient methodsClient = mock(MethodsClient.class);
        when(app.client()).thenReturn(methodsClient);
        SlackEventDeduplicator deduplicator = mock(SlackEventDeduplicator.class);
        RateLimitingService rateLimitingService = mock(RateLimitingService.class);
        when(rateLimitingService.tryAcquire("user-1")).thenReturn(false);
        when(rateLimitingService.getCooldownSeconds()).thenReturn(5L);
        SlackEventListener asyncSelf = mock(SlackEventListener.class);
        SlackEventListener listener = new SlackEventListener(
                app, mock(SlackAgentPipeline.class), deduplicator, rateLimitingService, asyncSelf);
        SlackMessageContext ctx = SlackMessageContext.builder()
                .eventId("event-123")
                .userId("user-1")
                .channelId("chan-1")
                .build();

        listener.handleAppMention(ctx);

        verify(methodsClient).chatPostMessage(any(RequestConfigurator.class));
        verify(deduplicator, never()).tryBegin(anyString());
        verify(asyncSelf, never()).processAppMention(any());
    }

    @Test
    void should_check_rate_limit_before_dedup_and_dispatch_when_allowed() {
        SlackEventDeduplicator deduplicator = mock(SlackEventDeduplicator.class);
        when(deduplicator.tryBegin("event-123")).thenReturn(true);
        RateLimitingService rateLimitingService = mock(RateLimitingService.class);
        when(rateLimitingService.tryAcquire("user-1")).thenReturn(true);
        SlackEventListener asyncSelf = mock(SlackEventListener.class);
        SlackEventListener listener = new SlackEventListener(
                null, mock(SlackAgentPipeline.class), deduplicator, rateLimitingService, asyncSelf);
        SlackMessageContext ctx = SlackMessageContext.builder()
                .eventId("event-123")
                .userId("user-1")
                .channelId("chan-1")
                .build();

        listener.handleAppMention(ctx);

        InOrder inOrder = inOrder(rateLimitingService, deduplicator, asyncSelf);
        inOrder.verify(rateLimitingService).tryAcquire("user-1");
        inOrder.verify(deduplicator).tryBegin("event-123");
        inOrder.verify(asyncSelf).processAppMention(ctx);
    }

    @Test
    void should_skip_dispatch_when_event_is_duplicate() {
        SlackEventDeduplicator deduplicator = mock(SlackEventDeduplicator.class);
        when(deduplicator.tryBegin("event-123")).thenReturn(false);
        RateLimitingService rateLimitingService = mock(RateLimitingService.class);
        when(rateLimitingService.tryAcquire("user-1")).thenReturn(true);
        SlackEventListener asyncSelf = mock(SlackEventListener.class);
        SlackEventListener listener = new SlackEventListener(
                null, mock(SlackAgentPipeline.class), deduplicator, rateLimitingService, asyncSelf);
        SlackMessageContext ctx = SlackMessageContext.builder()
                .eventId("event-123")
                .userId("user-1")
                .build();

        listener.handleAppMention(ctx);

        verify(asyncSelf, never()).processAppMention(any());
    }
}
