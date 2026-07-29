package com.java.system.agent.slack.delivery;

import com.java.system.agent.interaction.domain.delivery.DeliveryMessage;
import com.java.system.agent.interaction.domain.delivery.DeliveryTransportResult;
import com.java.system.agent.interaction.port.out.DeliveryTransportPort;
import com.java.system.agent.slack.SlackLifecycleMetrics;
import com.java.system.agent.slack.source.SlackDeliveryMetadataGuard;
import com.java.system.agent.slack.source.SlackSourceIdentity;
import com.java.system.agent.slack.source.SlackSourceIdentityCodec;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.model.Message;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Locale;

/**
 * 將 durable delivery outbox 訊息送往 Slack chat.postMessage
 */
public final class SlackDeliveryAdapter implements DeliveryTransportPort {

    private static final Set<String> PERMANENT_ERRORS = Set.of(
            "access_denied", "accesslimited", "account_inactive", "app_access_restricted",
            "as_user_not_supported", "attachment_payload_limit_exceeded", "cannot_reply_to_message",
            "channel_not_found", "deprecated_endpoint", "duplicate_channel_not_found",
            "duplicate_message_not_found", "ekm_access_denied", "enterprise_is_restricted",
            "invalid_arg_name", "invalid_arguments", "invalid_array_arg", "invalid_auth", "invalid_blocks",
            "invalid_blocks_format", "invalid_charset", "invalid_form_data", "invalid_metadata_format",
            "invalid_metadata_schema", "invalid_post_type", "is_archived", "markdown_text_conflict",
            "messages_tab_disabled", "metadata_must_be_sent_from_app", "metadata_too_large",
            "method_deprecated", "missing_file_data", "missing_post_type", "missing_scope",
            "msg_blocks_too_long", "no_permission", "no_text", "not_allowed_token_type", "not_authed",
            "not_in_channel", "restricted_action", "restricted_action_non_threadable_channel",
            "restricted_action_read_only_channel", "restricted_action_thread_locked",
            "restricted_action_thread_only_channel", "slack_connect_canvas_sharing_blocked",
            "slack_connect_file_link_sharing_blocked", "slack_connect_lists_sharing_blocked",
            "team_access_not_granted", "team_not_found", "token_expired", "token_revoked",
            "too_many_attachments");

    private final MethodsClient methods;
    private final String botToken;
    private final SlackSourceIdentityCodec identityCodec;
    private final SlackChannelRateGate rateGate;
    private final SlackLifecycleMetrics metrics;

    public SlackDeliveryAdapter(
            MethodsClient methods,
            String botToken,
            SlackSourceIdentityCodec identityCodec,
            SlackChannelRateGate rateGate) {
        this(methods, botToken, identityCodec, rateGate, SlackLifecycleMetrics.NO_OP);
    }

    public SlackDeliveryAdapter(
            MethodsClient methods,
            String botToken,
            SlackSourceIdentityCodec identityCodec,
            SlackChannelRateGate rateGate,
            SlackLifecycleMetrics metrics) {
        this.methods = Objects.requireNonNull(methods, "Slack methods client must not be null");
        this.botToken = Objects.requireNonNull(botToken, "Slack bot token must not be null");
        this.identityCodec = Objects.requireNonNull(identityCodec, "Slack source identity codec must not be null");
        this.rateGate = Objects.requireNonNull(rateGate, "Slack channel rate gate must not be null");
        this.metrics = Objects.requireNonNull(metrics, "agent lifecycle metrics must not be null");
    }

    @Override
    public DeliveryTransportResult deliver(DeliveryMessage message, Instant now) {
        Objects.requireNonNull(message, "delivery message must not be null");
        Objects.requireNonNull(now, "delivery time must not be null");
        SlackSourceIdentity target;
        try {
            target = identityCodec.decodeSession(message.sessionSource());
        } catch (IllegalArgumentException exception) {
            return new DeliveryTransportResult.PermanentFailure("SLACK_DELIVERY_IDENTITY", "Slack delivery identity is unavailable");
        }
        Optional<Instant> nextSafe = rateGate.reserve(target.channel(), now);
        if (nextSafe.isPresent()) {
            return new DeliveryTransportResult.RetryableFailure(nextSafe.orElseThrow(), "SLACK_CHANNEL_RATE_GATE");
        }
        try {
            ChatPostMessageResponse response = methods.chatPostMessage(request(message, target));
            if (response.isOk() && org.springframework.util.StringUtils.hasText(response.getTs())) {
                return new DeliveryTransportResult.Delivered(response.getTs());
            }
            return classifyResponse(response, now, target.channel());
        } catch (SlackApiException exception) {
            return classifyException(exception, now, target.channel());
        } catch (IOException exception) {
            return new DeliveryTransportResult.RetryableFailure(now, "SLACK_AMBIGUOUS_IO");
        }
    }

    private ChatPostMessageRequest request(DeliveryMessage message, SlackSourceIdentity target) {
        Map<String, Object> payload = Map.of(
                "delivery_id", message.deliveryId().value(),
                "run_id", message.runId().value());
        return ChatPostMessageRequest.builder()
                .token(botToken)
                .channel(target.channel())
                .threadTs(target.rootTimestamp())
                .text("<@" + message.participant().participantKey() + "> " + escapeMrkdwn(message.responseText()))
                .unfurlLinks(false)
                .unfurlMedia(false)
                .metadata(Message.Metadata.builder().eventType(SlackDeliveryMetadataGuard.EVENT_TYPE).eventPayload(payload).build())
                .build();
    }

    private DeliveryTransportResult classifyResponse(ChatPostMessageResponse response, Instant now, String channel) {
        String error = response.getError();
        if (PERMANENT_ERRORS.contains(error)) {
            return permanentFailure(error);
        }
        Instant retryAt = retryAfter(response.getHttpResponseHeaders(), now).orElse(now);
        rateGate.extend(channel, retryAt);
        return new DeliveryTransportResult.RetryableFailure(retryAt, "SLACK_API_RETRYABLE");
    }

    private DeliveryTransportResult classifyException(SlackApiException exception, Instant now, String channel) {
        int status = exception.getResponse().code();
        if (status == 429) {
            metrics.providerRateLimited();
            Instant retryAt = retryAfter(exception.getResponse().header("Retry-After"), now).orElse(now);
            rateGate.extend(channel, retryAt);
            return new DeliveryTransportResult.RetryableFailure(retryAt, "SLACK_RATE_LIMIT");
        }
        if (Objects.nonNull(exception.getError()) && PERMANENT_ERRORS.contains(exception.getError().getError())) {
            return permanentFailure(exception.getError().getError());
        }
        return new DeliveryTransportResult.RetryableFailure(now, "SLACK_API_RETRYABLE");
    }

    private static DeliveryTransportResult.PermanentFailure permanentFailure(String error) {
        return new DeliveryTransportResult.PermanentFailure(
                "SLACK_" + error.toUpperCase(Locale.ROOT), "Slack rejected delivery authorization or channel access");
    }

    private static String escapeMrkdwn(String responseText) {
        return responseText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static Optional<Instant> retryAfter(Map<String, java.util.List<String>> headers, Instant now) {
        if (Objects.isNull(headers)) {
            return Optional.empty();
        }
        for (Map.Entry<String, java.util.List<String>> header : headers.entrySet()) {
            if ("Retry-After".equalsIgnoreCase(header.getKey()) && !header.getValue().isEmpty()) {
                return retryAfter(header.getValue().getFirst(), now);
            }
        }
        return Optional.empty();
    }

    private static Optional<Instant> retryAfter(String value, Instant now) {
        try {
            long seconds = Long.parseLong(value);
            if (seconds <= 0) {
                return Optional.empty();
            }
            return Optional.of(now.plusSeconds(seconds));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }
}
