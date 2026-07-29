package com.java.system.agent.interaction.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/**
 * 以固定 framing 比較來源 payload 一致性的第一版摘要
 */
public record SourcePayloadFingerprintV1(byte[] bytes) {

    public static final String VERSION = "v1";
    private static final int DIGEST_LENGTH = 32;

    public SourcePayloadFingerprintV1 {
        Objects.requireNonNull(bytes, "fingerprint bytes must not be null");
        if (bytes.length != DIGEST_LENGTH) {
            throw new IllegalArgumentException("fingerprint must contain exactly 32 bytes");
        }
        bytes = bytes.clone();
    }

    /**
     * 依 canonical 欄位順序產生摘要
     */
    public static SourcePayloadFingerprintV1 fromCanonicalFields(
            String workspace,
            String channel,
            String messageTs,
            String rootThreadTs,
            String authorId,
            String sourceText) {
        String[] values = {
                requiredValue(workspace, "workspace"),
                requiredValue(channel, "channel"),
                requiredValue(messageTs, "message timestamp"),
                requiredValue(rootThreadTs, "root thread timestamp"),
                requiredValue(authorId, "author ID"),
                requiredValue(sourceText, "source text")
        };
        return new SourcePayloadFingerprintV1(digest(framedValues(values)));
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) { // cs-allow
            return true;
        }
        return other instanceof SourcePayloadFingerprintV1 fingerprint
                && Arrays.equals(bytes, fingerprint.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    private static String requiredValue(String value, String fieldName) {
        return Objects.requireNonNull(value, fieldName + " must not be null");
    }

    private static byte[] framedValues(String[] values) {
        byte[][] encodedValues = Arrays.stream(values)
                .map(value -> value.getBytes(StandardCharsets.UTF_8))
                .toArray(byte[][]::new);
        int requiredCapacity = Arrays.stream(encodedValues)
                .mapToInt(value -> Integer.BYTES + value.length)
                .sum();
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + VERSION.getBytes(StandardCharsets.UTF_8).length + requiredCapacity);
        appendFrame(buffer, VERSION.getBytes(StandardCharsets.UTF_8));
        for (byte[] encodedValue : encodedValues) {
            appendFrame(buffer, encodedValue);
        }
        return buffer.array();
    }

    private static void appendFrame(ByteBuffer buffer, byte[] value) {
        buffer.putInt(value.length);
        buffer.put(value);
    }

    private static byte[] digest(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
