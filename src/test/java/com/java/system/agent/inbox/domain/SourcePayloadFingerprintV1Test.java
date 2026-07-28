package com.java.system.agent.inbox.domain;

import com.java.system.agent.runtime.domain.conversation.ParticipantRef;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SourcePayloadFingerprintV1 的 canonical payload framing 測試
 */
class SourcePayloadFingerprintV1Test {

    @Test
    void hashesTheVersionedCanonicalFieldsWithBigEndianUtf8Lengths() {
        SourcePayloadFingerprintV1 fingerprint = SourcePayloadFingerprintV1.fromCanonicalFields(
                "workspace", "channel", "1710000000.000001", "1710000000.000001", "U123", "請分析🙂");

        assertThat(fingerprint.bytes()).hasSize(32);
        assertThat(fingerprint).isEqualTo(new SourcePayloadFingerprintV1(sha256(
                SourcePayloadFingerprintV1.VERSION,
                "workspace",
                "channel",
                "1710000000.000001",
                "1710000000.000001",
                "U123",
                "請分析🙂")));
        assertThat(fingerprint.bytes()).isNotSameAs(fingerprint.bytes());
    }

    @Test
    void retainsTheOriginalDigestWhenTheConstructorInputIsMutated() {
        byte[] inputBytes = sha256(SourcePayloadFingerprintV1.VERSION, "workspace", "channel", "message", "root", "author", "text");
        byte[] expectedBytes = inputBytes.clone();
        SourcePayloadFingerprintV1 fingerprint = new SourcePayloadFingerprintV1(inputBytes);
        SourcePayloadFingerprintV1 expected = new SourcePayloadFingerprintV1(expectedBytes);
        int originalHashCode = fingerprint.hashCode();

        inputBytes[0] = (byte) (inputBytes[0] ^ 0x01);

        assertThat(fingerprint).isEqualTo(expected);
        assertThat(fingerprint.hashCode()).isEqualTo(originalHashCode);
        assertThat(fingerprint.bytes()).containsExactly(expectedBytes);
    }

    @Test
    void distinguishesCanonicalValuesThatWouldCollideWithDelimiterFraming() {
        SourcePayloadFingerprintV1 first = SourcePayloadFingerprintV1.fromCanonicalFields(
                "workspace", "channel", "message", "root", "author", "left|right");
        SourcePayloadFingerprintV1 second = SourcePayloadFingerprintV1.fromCanonicalFields(
                "workspace", "channel", "message", "root|author", "left", "right");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void usesUtf8ByteLengthRatherThanCharacterLength() {
        String sourceText = "中🙂";
        SourcePayloadFingerprintV1 fingerprint = SourcePayloadFingerprintV1.fromCanonicalFields(
                "workspace", "channel", "message", "root", "author", sourceText);

        assertThat(fingerprint).isEqualTo(new SourcePayloadFingerprintV1(sha256(
                SourcePayloadFingerprintV1.VERSION,
                "workspace",
                "channel",
                "message",
                "root",
                "author",
                sourceText)));
    }

    @Test
    void excludesTransportAndDerivedEventValuesFromTheCanonicalFingerprint() {
        SourcePayloadFingerprintV1 fingerprint = SourcePayloadFingerprintV1.fromCanonicalFields(
                "workspace", "channel", "message", "root", "author", "<@U999> question");
        NormalizedSourceEvent original = event(
                "event-1", "<@U999> question", " question", Instant.parse("2026-07-28T01:00:00Z"), fingerprint);
        NormalizedSourceEvent redelivered = event(
                "event-2",
                "<@U999> question",
                "question",
                Instant.parse("2026-07-28T01:01:00Z"),
                SourcePayloadFingerprintV1.fromCanonicalFields(
                        "workspace", "channel", "message", "root", "author", "<@U999> question"));

        assertThat(original.fingerprint()).isEqualTo(redelivered.fingerprint());
    }

    private static NormalizedSourceEvent event(
            String eventId,
            String sourceText,
            String questionText,
            Instant receivedAt,
            SourcePayloadFingerprintV1 fingerprint) {
        return new NormalizedSourceEvent(
                "slack",
                new TransportEventId(eventId),
                new SourceMessageId("workspace:channel:message"),
                new SessionSourceRef("slack", "workspace:channel:root"),
                new ParticipantRef("slack", "author"),
                sourceText,
                questionText,
                fingerprint,
                receivedAt);
    }

    private static byte[] sha256(String... values) {
        ByteBuffer buffer = ByteBuffer.allocate(Arrays.stream(values)
                .map(value -> value.getBytes(StandardCharsets.UTF_8).length + Integer.BYTES)
                .reduce(0, Integer::sum));
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            buffer.putInt(bytes.length);
            buffer.put(bytes);
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(buffer.array());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
