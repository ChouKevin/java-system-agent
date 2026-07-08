package com.java.system.agent.slack.listener;

import com.java.system.agent.slack.model.SlackMessageContext;
import com.java.system.agent.slack.pipeline.SlackAgentPipeline;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SlackEventListenerTest {

    @Test
    void processAppMention_abandonsDedupClaim_whenPipelineFails() {
        SlackAgentPipeline pipeline = mock(SlackAgentPipeline.class);
        SlackEventDeduplicator deduplicator = mock(SlackEventDeduplicator.class);
        SlackMessageContext ctx = SlackMessageContext.builder()
                .eventId("event-123")
                .build();
        doThrow(new RuntimeException("boom")).when(pipeline).execute(ctx);
        SlackEventListener listener = new SlackEventListener(null, pipeline, deduplicator, null, null);

        listener.processAppMention(ctx);

        verify(deduplicator).abandon("event-123");
    }

    @Test
    void processAppMention_keepsDedupClaim_whenPipelineSucceeds() {
        SlackAgentPipeline pipeline = mock(SlackAgentPipeline.class);
        SlackEventDeduplicator deduplicator = mock(SlackEventDeduplicator.class);
        SlackMessageContext ctx = SlackMessageContext.builder()
                .eventId("event-123")
                .build();
        SlackEventListener listener = new SlackEventListener(null, pipeline, deduplicator, null, null);

        listener.processAppMention(ctx);

        verify(deduplicator, never()).abandon("event-123");
    }
}
