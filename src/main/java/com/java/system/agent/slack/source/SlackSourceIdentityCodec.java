package com.java.system.agent.slack.source;

import com.java.system.agent.inbox.domain.SessionSourceRef;
import com.java.system.agent.inbox.domain.SourceMessageId;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import org.springframework.util.StringUtils;

/**
 * Slack 身分與 inbox opaque contract 間唯一的編解碼邊界
 */
public final class SlackSourceIdentityCodec {

    private static final String SOURCE_TYPE = "slack";
    private static final int FIELD_COUNT = 3;
    private static final int MAX_FIELD_CHARACTERS = 512;
    private static final int MAX_FIELD_BYTES = 512;
    private static final int MAX_DECODED_BYTES = FIELD_COUNT * (Integer.BYTES + MAX_FIELD_BYTES);
    private static final int MAX_ENCODED_CHARACTERS = ((MAX_DECODED_BYTES + 2) / 3) * 4;

    /**
     * 編碼 canonical Slack 訊息身分
     */
    public SourceMessageId sourceMessageId(SlackSourceIdentity identity) {
        Objects.requireNonNull(identity, "Slack source identity must not be null");
        return new SourceMessageId(encode(identity.workspace(), identity.channel(), identity.messageTimestamp()));
    }

    /**
     * 編碼 Slack thread session 身分
     */
    public SessionSourceRef sessionSource(SlackSourceIdentity identity) {
        Objects.requireNonNull(identity, "Slack source identity must not be null");
        return new SessionSourceRef(SOURCE_TYPE, encode(identity.workspace(), identity.channel(), identity.rootTimestamp()));
    }

    /**
     * 解碼 canonical Slack 訊息身分
     */
    public SlackSourceIdentity decodeSourceMessage(SourceMessageId sourceMessageId) {
        Objects.requireNonNull(sourceMessageId, "source message ID must not be null");
        String[] values = decode(sourceMessageId.value());
        return new SlackSourceIdentity(values[0], values[1], values[2], values[2]);
    }

    /**
     * 解碼 Slack delivery session 身分
     */
    public SlackSourceIdentity decodeSession(SessionSourceRef sessionSource) {
        Objects.requireNonNull(sessionSource, "session source must not be null");
        if (!SOURCE_TYPE.equals(sessionSource.sourceType())) {
            throw new IllegalArgumentException("delivery session source must be slack");
        }
        String[] values = decode(sessionSource.sourceKey());
        return new SlackSourceIdentity(values[0], values[1], values[2], values[2]);
    }

    private static String encode(String... values) {
        List<byte[]> fields = new ArrayList<>(FIELD_COUNT);
        int length = 0;
        for (String value : values) {
            validateEncodeField(value);
            byte[] bytes = encodeUtf8(value);
            if (bytes.length > MAX_FIELD_BYTES) {
                throw new IllegalArgumentException("Slack source identity field is too large");
            }
            fields.add(bytes);
            length += Integer.BYTES + bytes.length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(length);
        for (byte[] field : fields) {
            buffer.putInt(field.length);
            buffer.put(field);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }

    private static String[] decode(String encoded) {
        try {
            if (!StringUtils.hasText(encoded) || encoded.length() > MAX_ENCODED_CHARACTERS
                    || !encoded.matches("[A-Za-z0-9_-]+")) {
                throw malformedIdentity();
            }
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (bytes.length > MAX_DECODED_BYTES
                    || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded)) {
                throw malformedIdentity();
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            String[] values = new String[FIELD_COUNT];
            for (int index = 0; index < FIELD_COUNT; index++) {
                if (buffer.remaining() < Integer.BYTES) {
                    throw malformedIdentity();
                }
                int length = buffer.getInt();
                if (length <= 0 || length > MAX_FIELD_BYTES || length > buffer.remaining()) {
                    throw malformedIdentity();
                }
                byte[] field = new byte[length];
                buffer.get(field);
                values[index] = decodeUtf8(field);
                if (!StringUtils.hasText(values[index])) {
                    throw malformedIdentity();
                }
            }
            if (buffer.hasRemaining()) {
                throw malformedIdentity();
            }
            return values;
        } catch (CharacterCodingException | RuntimeException exception) {
            throw malformedIdentity();
        }
    }

    private static void validateEncodeField(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Slack source identity fields must not be blank");
        }
        if (value.length() > MAX_FIELD_CHARACTERS) {
            throw new IllegalArgumentException("Slack source identity field is too large");
        }
    }

    private static String decodeUtf8(byte[] field) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(field)).toString();
    }

    private static byte[] encodeUtf8(String field) {
        try {
            CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer encoded = encoder.encode(CharBuffer.wrap(field));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw malformedIdentity();
        }
    }

    private static IllegalArgumentException malformedIdentity() {
        return new IllegalArgumentException("malformed Slack source identity");
    }
}
