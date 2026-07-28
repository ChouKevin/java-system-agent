package com.java.system.agent.slack.source;

import com.slack.api.bolt.context.Context;
import com.slack.api.bolt.middleware.MiddlewareChain;
import com.slack.api.bolt.request.Request;
import com.slack.api.bolt.response.Response;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Slack delivery metadata middleware 的迴圈防護測試
 */
class SlackDeliveryMetadataGuardTest {

    @Test
    @SuppressWarnings("unchecked")
    void acknowledgesOwnDeliveryMetadataWithoutInvokingEventChain() throws Exception {
        Request<Context> request = mock(Request.class);
        Context context = mock(Context.class);
        MiddlewareChain chain = mock(MiddlewareChain.class);
        Response acknowledgement = Response.ok();
        when(request.getRequestBodyAsString()).thenReturn("""
                {"event":{"metadata":{"event_type":"java_system_agent_delivery_v1"}}}
                """);
        when(request.getContext()).thenReturn(context);
        when(context.ack()).thenReturn(acknowledgement);

        Response response = new SlackDeliveryMetadataGuard().apply(request, Response.ok(), chain);

        assertThat(response).isSameAs(acknowledgement);
        verify(chain, never()).next(request);
    }
}
