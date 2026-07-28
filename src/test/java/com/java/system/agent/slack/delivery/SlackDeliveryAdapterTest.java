package com.java.system.agent.slack.delivery;

import com.java.system.agent.inbox.domain.InboxMessageId;
import com.java.system.agent.inbox.domain.SessionSourceRef;
import com.java.system.agent.inbox.domain.delivery.DeliveryId;
import com.java.system.agent.inbox.domain.delivery.DeliveryKind;
import com.java.system.agent.inbox.domain.delivery.DeliveryMessage;
import com.java.system.agent.inbox.domain.delivery.DeliveryStatus;
import com.java.system.agent.inbox.domain.delivery.DeliveryTransportResult;
import com.java.system.agent.runtime.domain.conversation.ParticipantRef;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.run.RunResponseKind;
import com.java.system.agent.slack.source.SlackSourceIdentity;
import com.java.system.agent.slack.source.SlackSourceIdentityCodec;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import okhttp3.Protocol;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Slack delivery transport 映射測試
 */
class SlackDeliveryAdapterTest {

    @Test
    void postsAddressedThreadReplyWithStableMetadata() throws Exception {
        MethodsClient methods = mock(MethodsClient.class);
        ChatPostMessageResponse response = new ChatPostMessageResponse();
        response.setOk(true);
        response.setTs("20.1");
        when(methods.chatPostMessage(org.mockito.ArgumentMatchers.any(ChatPostMessageRequest.class))).thenReturn(response);
        SlackSourceIdentityCodec codec = new SlackSourceIdentityCodec();
        SessionSourceRef session = codec.sessionSource(new SlackSourceIdentity("T1", "C1", "10.1", "10.1"));
        SlackDeliveryAdapter adapter = new SlackDeliveryAdapter(methods, "xoxb-secret", codec, new SlackChannelRateGate());

        DeliveryTransportResult result = adapter.deliver(message(session), Instant.parse("2030-07-26T10:00:00Z"));

        org.mockito.ArgumentCaptor<ChatPostMessageRequest> request = forClass(ChatPostMessageRequest.class);
        verify(methods).chatPostMessage(request.capture());
        assertThat(result).isEqualTo(new DeliveryTransportResult.Delivered("20.1"));
        assertThat(request.getValue().getChannel()).isEqualTo("C1");
        assertThat(request.getValue().getThreadTs()).isEqualTo("10.1");
        assertThat(request.getValue().getText()).isEqualTo("<@U1> exact response");
        assertThat(request.getValue().isUnfurlLinks()).isFalse();
        assertThat(request.getValue().isUnfurlMedia()).isFalse();
        assertThat(request.getValue().getMetadata().getEventType()).isEqualTo("java_system_agent_delivery_v1");
        assertThat(request.getValue().getMetadata().getEventPayload()).containsEntry("delivery_id", "delivery-1").containsEntry("run_id", "run-1");
    }

    @Test
    void mapsSlackRateLimitToExactRetryAfterAndExtendsChannelGate() throws Exception {
        MethodsClient methods = mock(MethodsClient.class);
        when(methods.chatPostMessage(org.mockito.ArgumentMatchers.any(ChatPostMessageRequest.class)))
                .thenThrow(new SlackApiException(new okhttp3.Response.Builder()
                        .request(new okhttp3.Request.Builder().url("https://slack.com/api/chat.postMessage").build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .header("Retry-After", "42")
                        .build(), "safe"));
        SlackSourceIdentityCodec codec = new SlackSourceIdentityCodec();
        Instant now = Instant.parse("2030-07-26T10:00:00Z");
        SlackDeliveryAdapter adapter = new SlackDeliveryAdapter(methods, "xoxb-secret", codec, new SlackChannelRateGate());

        DeliveryTransportResult result = adapter.deliver(message(codec.sessionSource(
                new SlackSourceIdentity("T1", "C1", "10.1", "10.1"))), now);

        assertThat(result).isEqualTo(new DeliveryTransportResult.RetryableFailure(now.plusSeconds(42), "SLACK_RATE_LIMIT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "invalid", "9223372036854775808"})
    void mapsInvalidRetryAfterToNonAuthoritativeRetryTime(String retryAfter) throws Exception {
        MethodsClient methods = mock(MethodsClient.class);
        when(methods.chatPostMessage(org.mockito.ArgumentMatchers.any(ChatPostMessageRequest.class)))
                .thenThrow(new SlackApiException(new okhttp3.Response.Builder()
                        .request(new okhttp3.Request.Builder().url("https://slack.com/api/chat.postMessage").build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .header("Retry-After", retryAfter)
                        .build(), "safe"));
        SlackSourceIdentityCodec codec = new SlackSourceIdentityCodec();
        Instant now = Instant.parse("2030-07-26T10:00:00Z");
        SlackDeliveryAdapter adapter = new SlackDeliveryAdapter(methods, "xoxb-secret", codec, new SlackChannelRateGate());

        DeliveryTransportResult result = adapter.deliver(message(codec.sessionSource(
                new SlackSourceIdentity("T1", "C1", "10.1", "10.1"))), now);

        assertThat(result).isEqualTo(new DeliveryTransportResult.RetryableFailure(now, "SLACK_RATE_LIMIT"));
    }

    @Test
    void mapsAmbiguousIoFailureToRetryableResult() throws Exception {
        MethodsClient methods = mock(MethodsClient.class);
        when(methods.chatPostMessage(org.mockito.ArgumentMatchers.any(ChatPostMessageRequest.class)))
                .thenThrow(new java.net.SocketTimeoutException("timed out"));
        SlackSourceIdentityCodec codec = new SlackSourceIdentityCodec();
        Instant now = Instant.parse("2030-07-26T10:00:00Z");
        SlackDeliveryAdapter adapter = new SlackDeliveryAdapter(methods, "xoxb-secret", codec, new SlackChannelRateGate());

        assertThat(adapter.deliver(message(codec.sessionSource(new SlackSourceIdentity("T1", "C1", "10.1", "10.1"))), now))
                .isEqualTo(new DeliveryTransportResult.RetryableFailure(now, "SLACK_AMBIGUOUS_IO"));
    }

    @Test
    void escapesModelControlledSlackMarkupWhileKeepingOnlyTheParticipantPrefix() throws Exception {
        MethodsClient methods = mock(MethodsClient.class);
        ChatPostMessageResponse response = new ChatPostMessageResponse();
        response.setOk(true);
        response.setTs("20.1");
        when(methods.chatPostMessage(org.mockito.ArgumentMatchers.any(ChatPostMessageRequest.class))).thenReturn(response);
        SlackSourceIdentityCodec codec = new SlackSourceIdentityCodec();
        SlackDeliveryAdapter adapter = new SlackDeliveryAdapter(methods, "xoxb-secret", codec, new SlackChannelRateGate());

        adapter.deliver(message(codec.sessionSource(new SlackSourceIdentity("T1", "C1", "10.1", "10.1")),
                "<!channel> <!here> <@UOTHER> <https://example.test|link> &"), Instant.EPOCH);

        org.mockito.ArgumentCaptor<ChatPostMessageRequest> request = forClass(ChatPostMessageRequest.class);
        verify(methods).chatPostMessage(request.capture());
        assertThat(request.getValue().getText()).isEqualTo(
                "<@U1> &lt;!channel&gt; &lt;!here&gt; &lt;@UOTHER&gt; &lt;https://example.test|link&gt; &amp;");
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid_auth", "account_inactive", "token_revoked", "channel_not_found", "not_in_channel",
            "is_archived", "missing_scope", "no_permission", "access_denied", "team_access_not_granted",
            "not_allowed_token_type", "invalid_arguments", "invalid_blocks", "invalid_blocks_format",
            "invalid_metadata_format", "no_text", "restricted_action", "restricted_action_thread_locked",
            "cannot_reply_to_message"})
    void mapsConfirmedSlackAuthorizationAndChannelErrorsToPermanentFailures(String error) throws Exception {
        MethodsClient methods = mock(MethodsClient.class);
        ChatPostMessageResponse response = new ChatPostMessageResponse();
        response.setOk(false);
        response.setError(error);
        when(methods.chatPostMessage(org.mockito.ArgumentMatchers.any(ChatPostMessageRequest.class))).thenReturn(response);
        SlackSourceIdentityCodec codec = new SlackSourceIdentityCodec();
        SlackDeliveryAdapter adapter = new SlackDeliveryAdapter(methods, "xoxb-secret", codec, new SlackChannelRateGate());

        DeliveryTransportResult result = adapter.deliver(message(codec.sessionSource(
                new SlackSourceIdentity("T1", "C1", "10.1", "10.1"))), Instant.EPOCH);

        assertThat(result).isInstanceOf(DeliveryTransportResult.PermanentFailure.class);
    }

    @Test
    void conservativelyRetriesUnknownFutureSlackErrorCodes() throws Exception {
        MethodsClient methods = mock(MethodsClient.class);
        ChatPostMessageResponse response = new ChatPostMessageResponse();
        response.setOk(false);
        response.setError("future_slack_error");
        when(methods.chatPostMessage(org.mockito.ArgumentMatchers.any(ChatPostMessageRequest.class))).thenReturn(response);
        SlackSourceIdentityCodec codec = new SlackSourceIdentityCodec();
        SlackDeliveryAdapter adapter = new SlackDeliveryAdapter(methods, "xoxb-secret", codec, new SlackChannelRateGate());

        assertThat(adapter.deliver(
                message(codec.sessionSource(new SlackSourceIdentity("T1", "C1", "10.1", "10.1"))), Instant.EPOCH))
                .isEqualTo(new DeliveryTransportResult.RetryableFailure(Instant.EPOCH, "SLACK_API_RETRYABLE"));
    }

    @Test
    void rejectsMalformedOrNonSlackDeliveryIdentityWithoutCallingSlack() throws Exception {
        MethodsClient methods = mock(MethodsClient.class);
        SlackDeliveryAdapter adapter = new SlackDeliveryAdapter(methods, "xoxb-secret", new SlackSourceIdentityCodec(), new SlackChannelRateGate());

        DeliveryTransportResult result = adapter.deliver(message(new SessionSourceRef("other", "opaque")), Instant.EPOCH);

        assertThat(result).isEqualTo(new DeliveryTransportResult.PermanentFailure(
                "SLACK_DELIVERY_IDENTITY", "Slack delivery identity is unavailable"));
        verify(methods, org.mockito.Mockito.never()).chatPostMessage(org.mockito.ArgumentMatchers.any(ChatPostMessageRequest.class));
    }

    private static DeliveryMessage message(SessionSourceRef session) {
        return message(session, "exact response");
    }

    private static DeliveryMessage message(SessionSourceRef session, String responseText) {
        Instant now = Instant.parse("2030-07-26T10:00:00Z");
        return new DeliveryMessage(new DeliveryId("delivery-1"), new InboxMessageId("inbox-1"), new AnalysisRunId("run-1"),
                DeliveryKind.FINAL_RESPONSE, Optional.of(RunResponseKind.ANSWER), Optional.of(RunOutcome.COMPLETED), session,
                new ParticipantRef("slack", "U1"), responseText, DeliveryStatus.PROCESSING, 1, now, Optional.empty(),
                Optional.empty(), now, now);
    }
}
