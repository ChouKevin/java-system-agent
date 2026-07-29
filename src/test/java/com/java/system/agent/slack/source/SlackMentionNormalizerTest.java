package com.java.system.agent.slack.source;

import com.java.system.agent.interaction.domain.NormalizedSourceEvent;
import com.slack.api.model.BotProfile;
import com.slack.api.model.event.AppMentionEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slack mention 正規化與忽略條件測試
 */
class SlackMentionNormalizerTest {

    private final SlackMentionNormalizer normalizer = new SlackMentionNormalizer(
            "UBOT", Clock.fixed(Instant.parse("2030-07-26T10:00:00Z"), ZoneOffset.UTC), new SlackSourceIdentityCodec());

    @Test
    void normalizesHumanThreadReplyAndRemovesOnlyFirstInvocation() {
        AppMentionEvent event = supported("<@UBOT>  retain <@UBOT>  ", "C1", "12.2", "10.1");

        Optional<NormalizedSourceEvent> normalized = normalizer.normalize("Ev1", event);

        assertThat(normalized).isPresent();
        assertThat(normalized.orElseThrow().sourceText()).isEqualTo("<@UBOT>  retain <@UBOT>  ");
        assertThat(normalized.orElseThrow().questionText()).isEqualTo("  retain <@UBOT>  ");
        assertThat(normalized.orElseThrow().participant().sourceType()).isEqualTo("slack");
        assertThat(normalized.orElseThrow().participant().participantKey()).isEqualTo("U1");
        assertThat(normalized.orElseThrow().fingerprint().bytes()).hasSize(32);
    }

    @Test
    void ignoresUnsupportedOrBlankMentions() {
        AppMentionEvent blank = supported("<@UBOT>", "C1", "12.2", "");
        AppMentionEvent directMessage = supported("<@UBOT> question", "D1", "12.3", "");
        AppMentionEvent botAuthored = supported("<@UBOT> question", "C1", "12.4", "");
        botAuthored.setBotId("B1");
        AppMentionEvent botProfiled = supported("<@UBOT> question", "C1", "12.45", "");
        botProfiled.setBotProfile(new BotProfile());
        AppMentionEvent unaddressed = supported("question without a bot token", "C1", "12.46", "");
        AppMentionEvent anotherUser = supported("<@UOTHER> question", "C1", "12.47", "");
        AppMentionEvent edited = supported("<@UBOT> question", "C1", "12.5", "");
        edited.setSubtype("message_changed");
        AppMentionEvent self = supported("<@UBOT> question", "C1", "12.6", "");
        self.setUser("UBOT");
        AppMentionEvent privateChannel = supported("<@UBOT> question", "G1", "12.7", "");

        assertThat(normalizer.normalize("Ev1", blank)).isEmpty();
        assertThat(normalizer.normalize("Ev2", directMessage)).isEmpty();
        assertThat(normalizer.normalize("Ev3", botAuthored)).isEmpty();
        assertThat(normalizer.normalize("Ev4", botProfiled)).isEmpty();
        assertThat(normalizer.normalize("Ev5", unaddressed)).isEmpty();
        assertThat(normalizer.normalize("Ev6", anotherUser)).isEmpty();
        assertThat(normalizer.normalize("Ev7", edited)).isEmpty();
        assertThat(normalizer.normalize("Ev8", self)).isEmpty();
        assertThat(normalizer.normalize("Ev9", privateChannel)).isPresent();
    }

    private static AppMentionEvent supported(String text, String channel, String ts, String threadTs) {
        AppMentionEvent event = new AppMentionEvent();
        event.setTeam("T1");
        event.setChannel(channel);
        event.setUser("U1");
        event.setText(text);
        event.setTs(ts);
        event.setThreadTs(threadTs);
        return event;
    }
}
