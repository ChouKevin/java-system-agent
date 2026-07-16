package com.java.system.agent.slack.pipeline;

import com.java.system.agent.ai.service.AgentAiService;
import com.java.system.agent.ai.trace.AgentRequestContext;
import com.java.system.agent.slack.client.SlackStreamClient;
import com.java.system.agent.slack.client.SlackStreamFailureHandler;
import com.java.system.agent.slack.model.SlackMessageContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlackAgentPipelineTest {

    @Mock
    private AgentAiService agentAiService;

    @Mock
    private SlackStreamClient slackStreamClient;

    @InjectMocks
    private SlackAgentPipeline pipeline;

    @Test
    @SuppressWarnings("unchecked")
    void should_pass_stable_request_context_when_executing() {
        SlackMessageContext ctx = SlackMessageContext.builder()
                .traceId("trace-1")
                .userId("U1")
                .teamId("T1")
                .channelId("C1")
                .eventId("E1")
                .threadTs("123.456")
                .text("問題")
                .build();
        SlackStreamFailureHandler failureHandler = mock(SlackStreamFailureHandler.class);
        ArgumentCaptor<Supplier<Flux<String>>> supplierCaptor = ArgumentCaptor.forClass(Supplier.class);
        when(agentAiService.analyzeWithTools(any(AgentRequestContext.class))).thenReturn(Flux.just("回答"));

        pipeline.execute(ctx, failureHandler);

        verify(slackStreamClient).consumeStream(eq(ctx), eq(""), supplierCaptor.capture(), eq(failureHandler));
        supplierCaptor.getValue().get().collectList().block();
        ArgumentCaptor<AgentRequestContext> contextCaptor = ArgumentCaptor.forClass(AgentRequestContext.class);
        verify(agentAiService).analyzeWithTools(contextCaptor.capture());
        assertThat(contextCaptor.getValue().traceId()).isEqualTo(ctx.getTraceId());
        assertThat(contextCaptor.getValue().conversationId()).isEqualTo(ctx.getThreadTs());
        assertThat(contextCaptor.getValue().eventId()).isEqualTo(ctx.getEventId());
    }
}
