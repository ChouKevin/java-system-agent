package com.java.system.agent.slack.source;

import com.java.system.agent.interaction.domain.NormalizedSourceEvent;
import com.java.system.agent.interaction.domain.SourcePayloadFingerprintV1;
import com.java.system.agent.interaction.domain.TransportEventId;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.slack.SlackLifecycleMetrics;
import com.slack.api.model.event.AppMentionEvent;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * 將可接受的 Slack mention 壓縮成 transport-neutral inbox 事件
 */
public final class SlackMentionNormalizer {

    private final String botUserId;
    private final Clock clock;
    private final SlackSourceIdentityCodec identityCodec;
    private final SlackLifecycleMetrics metrics;

    public SlackMentionNormalizer(String botUserId, Clock clock, SlackSourceIdentityCodec identityCodec) {
        this(botUserId, clock, identityCodec, SlackLifecycleMetrics.NO_OP);
    }

    public SlackMentionNormalizer(
            String botUserId,
            Clock clock,
            SlackSourceIdentityCodec identityCodec,
            SlackLifecycleMetrics metrics) {
        if (!StringUtils.hasText(botUserId)) {
            throw new IllegalArgumentException("Slack bot user ID must not be blank");
        }
        this.botUserId = botUserId;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.identityCodec = Objects.requireNonNull(identityCodec, "Slack source identity codec must not be null");
        this.metrics = Objects.requireNonNull(metrics, "agent lifecycle metrics must not be null");
    }

    /**
     * 正規化一筆 Slack app mention 或安全地忽略它
     */
    public Optional<NormalizedSourceEvent> normalize(String eventId, AppMentionEvent event) {
        Objects.requireNonNull(event, "Slack app mention event must not be null");
        if (!isSupported(event) || !StringUtils.hasText(eventId)) {
            metrics.unsupportedMentionIgnored();
            return Optional.empty();
        }
        String sourceText = event.getText();
        if (!sourceText.contains(invocation())) {
            metrics.unsupportedMentionIgnored();
            return Optional.empty();
        }
        String questionText = removeFirstInvocation(sourceText);
        if (!StringUtils.hasText(questionText)) {
            metrics.unsupportedMentionIgnored();
            return Optional.empty();
        }
        SlackSourceIdentity identity = SlackSourceIdentity.fromEvent(
                event.getTeam(), event.getChannel(), event.getTs(), event.getThreadTs());
        return Optional.of(new NormalizedSourceEvent(
                "slack",
                new TransportEventId(eventId),
                identityCodec.sourceMessageId(identity),
                identityCodec.sessionSource(identity),
                new ParticipantRef("slack", event.getUser()),
                sourceText,
                questionText,
                SourcePayloadFingerprintV1.fromCanonicalFields(
                        identity.workspace(), identity.channel(), identity.messageTimestamp(), identity.rootTimestamp(),
                        event.getUser(), sourceText),
                clock.instant()));
    }

    private boolean isSupported(AppMentionEvent event) {
        return StringUtils.hasText(event.getTeam())
                && StringUtils.hasText(event.getChannel())
                && (event.getChannel().startsWith("C") || event.getChannel().startsWith("G"))
                && StringUtils.hasText(event.getUser())
                && !botUserId.equals(event.getUser())
                && !StringUtils.hasText(event.getBotId())
                && Objects.isNull(event.getBotProfile())
                && !StringUtils.hasText(event.getSubtype())
                && StringUtils.hasText(event.getText())
                && StringUtils.hasText(event.getTs());
    }

    private String removeFirstInvocation(String sourceText) {
        String invocation = invocation();
        int position = sourceText.indexOf(invocation);
        if (position < 0) {
            return sourceText;
        }
        return sourceText.substring(0, position) + sourceText.substring(position + invocation.length());
    }

    private String invocation() {
        return "<@" + botUserId + ">";
    }
}
