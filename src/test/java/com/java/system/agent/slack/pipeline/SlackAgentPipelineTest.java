package com.java.system.agent.slack.pipeline;

import com.java.system.agent.ai.service.AgentAiService;
import com.java.system.agent.slack.client.SlackStreamClient;
import com.java.system.agent.slack.model.SlackMessageContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SlackAgentPipelineTest {

    @Mock
    private AgentAiService agentAiService;

    @Mock
    private SlackStreamClient slackStreamClient;

    @InjectMocks
    private SlackAgentPipeline pipeline;

    @Test
    void execute_callsConsumeStream_withContextAndEmptyInitialText() {
        SlackMessageContext ctx = mock(SlackMessageContext.class);

        pipeline.execute(ctx);

        verify(slackStreamClient).consumeStream(eq(ctx), eq(""), any());
    }
}
