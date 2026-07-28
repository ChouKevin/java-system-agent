package com.java.system.agent.slack.source;

import com.java.system.agent.inbox.domain.SessionSourceRef;
import com.java.system.agent.inbox.domain.SourceMessageId;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slack opaque source identity codec 的可逆性測試
 */
class SlackSourceIdentityCodecTest {

    @Test
    void roundTripsLengthPrefixedUrlSafeIdentitiesWithoutPadding() {
        SlackSourceIdentityCodec codec = new SlackSourceIdentityCodec();
        SlackSourceIdentity identity = new SlackSourceIdentity("T workspace", "C123", "1710000000.100200", "1710000000.000100");

        SourceMessageId sourceMessageId = codec.sourceMessageId(identity);
        SessionSourceRef sessionSource = codec.sessionSource(identity);

        assertThat(sourceMessageId.value()).doesNotContain("=").matches("[A-Za-z0-9_-]+");
        assertThat(sessionSource.sourceKey()).doesNotContain("=").matches("[A-Za-z0-9_-]+");
        assertThat(codec.decodeSourceMessage(sourceMessageId)).isEqualTo(new SlackSourceIdentity(
                "T workspace", "C123", "1710000000.100200", "1710000000.100200"));
        assertThat(codec.decodeSession(sessionSource)).isEqualTo(identity.sessionIdentity());
    }

    @Test
    void usesMessageTimestampAsRootWhenThreadTimestampIsBlank() {
        SlackSourceIdentity identity = SlackSourceIdentity.fromEvent("T1", "C1", "10.0001", " ");

        assertThat(identity.rootTimestamp()).isEqualTo("10.0001");
    }

    @Test
    void rejectsNonCanonicalAliasesAndTrailingCorruption() {
        SlackSourceIdentityCodec codec = new SlackSourceIdentityCodec();
        String identity = codec.sessionSource(new SlackSourceIdentity("T1", "C1", "10.1", "10.1")).sourceKey();

        assertMalformed(codec, identity + "=");
        assertMalformed(codec, identity + "A");
    }

    @Test
    void rejectsMalformedUtf8BlankAndOversizedIdentityFields() {
        SlackSourceIdentityCodec codec = new SlackSourceIdentityCodec();

        assertMalformed(codec, encode(new byte[]{(byte) 0xC3, 0x28}, "C1".getBytes(), "10.1".getBytes()));
        assertMalformed(codec, encode(new byte[0], "C1".getBytes(), "10.1".getBytes()));
        assertMalformed(codec, encode(new byte[513], "C1".getBytes(), "10.1".getBytes()));
        assertMalformed(codec, "A".repeat(4097));
    }

    @Test
    void rejectsUnpairedSurrogatesBeforeEncodingSessionIdentity() {
        SlackSourceIdentityCodec codec = new SlackSourceIdentityCodec();
        SlackSourceIdentity identity = new SlackSourceIdentity("T1\uD800", "C1", "10.1", "10.1");

        assertThatThrownBy(() -> codec.sessionSource(identity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("malformed Slack source identity");
    }

    private static void assertMalformed(SlackSourceIdentityCodec codec, String encoded) {
        assertThatThrownBy(() -> codec.decodeSession(new SessionSourceRef("slack", encoded)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("malformed Slack source identity");
    }

    private static String encode(byte[] first, byte[] second, byte[] third) {
        int capacity = Integer.BYTES * 3 + first.length + second.length + third.length;
        ByteBuffer buffer = ByteBuffer.allocate(capacity);
        writeField(buffer, first);
        writeField(buffer, second);
        writeField(buffer, third);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }

    private static void writeField(ByteBuffer buffer, byte[] field) {
        buffer.putInt(field.length);
        buffer.put(field);
    }
}
