package com.java.system.agent.model.action;

import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.action.AnswerAction;
import com.java.system.agent.answering.domain.action.ClarifyAction;
import com.java.system.agent.answering.domain.action.ExecuteAction;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.port.out.AgentPromptContext;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 將可重複選擇的 action 語意欄位轉為不洩漏內容的穩定摘要
 */
public record AgentActionFingerprint(String value) {

    private static final String SHA_256 = "SHA-256";

    public AgentActionFingerprint {
        Objects.requireNonNull(value, "agent action fingerprint value must not be null");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("agent action fingerprint must be a SHA-256 hex digest");
        }
    }

    /**
     * 依 action 實際交給 executor 的欄位產生可安全記錄的摘要
     */
    public static AgentActionFingerprint from(AgentAction action, AgentPromptContext context) {
        return create(action, context, true);
    }

    /**
     * 忽略 QUERY 問題措辭，用於判斷外部執行 payload 是否重複
     */
    public static AgentActionFingerprint executionPayloadFrom(AgentAction action, AgentPromptContext context) {
        return create(action, context, false);
    }

    private static AgentActionFingerprint create(
            AgentAction action,
            AgentPromptContext context,
            boolean includeQueryQuestion) {
        Objects.requireNonNull(action, "agent action must not be null");
        Objects.requireNonNull(context, "agent prompt context must not be null");
        MessageDigest digest = messageDigest();
        frame(digest, "format", "agent-action-fingerprint-v1");
        switch (action) {
            case QueryAction query -> query(digest, query, context, includeQueryQuestion);
            case ExecuteAction execute -> execute(digest, execute);
            case AnswerAction answer -> answer(digest, answer);
            case ClarifyAction clarify -> clarify(digest, clarify);
        }
        return new AgentActionFingerprint(HexFormat.of().formatHex(digest.digest()));
    }

    /**
     * 將文字轉為可安全記錄的 SHA-256 摘要
     */
    public static String sha256(String value) {
        Objects.requireNonNull(value, "SHA-256 input must not be null");
        MessageDigest digest = messageDigest();
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void query(
            MessageDigest digest,
            QueryAction action,
            AgentPromptContext context,
            boolean includeQuestion) {
        frame(digest, "actionType", "QUERY");
        Optional<CapabilityPolicy> capability = resolveCapability(action, context);
        if (capability.isPresent()) {
            CapabilityPolicy resolvedCapability = capability.orElseThrow();
            frame(digest, "capabilityName", resolvedCapability.name());
            frame(digest, "capabilityVersion", resolvedCapability.version());
        } else {
            frame(digest, "capabilityHandle", stableIssuedHandle(action.capability().value()));
        }
        sequence(digest, "candidateHandle", action.candidates().stream()
                .map(candidate -> stableIssuedHandle(candidate.value())).toList());
        if (includeQuestion) {
            frame(digest, "questionToResolve", action.questionToResolve());
        }
        frame(digest, "canonicalPayload", action.payload().value());
    }

    private static Optional<CapabilityPolicy> resolveCapability(QueryAction action, AgentPromptContext context) {
        String selectedHandle = stableIssuedHandle(action.capability().value());
        return context.issuedCapabilities().entrySet().stream()
                .filter(entry -> stableIssuedHandle(entry.getKey().value()).equals(selectedHandle))
                .map(entry -> entry.getValue())
                .findFirst();
    }

    private static String stableIssuedHandle(String value) {
        int separator = value.lastIndexOf(':');
        if (separator < 0 || separator == value.length() - 1) {
            return value;
        }
        String suffix = value.substring(separator + 1);
        return suffix.matches("[CRT][1-9][0-9]*") ? suffix : value;
    }

    private static void execute(MessageDigest digest, ExecuteAction action) {
        frame(digest, "actionType", "EXECUTE");
        frame(digest, "method", action.method().name());
        frame(digest, "targetUrl", action.targetUrl());
        frame(digest, "jsonBodyPresent", Boolean.toString(action.jsonBody().isPresent()));
        action.jsonBody().ifPresent(body -> frame(digest, "jsonBody", body));
    }

    private static void answer(MessageDigest digest, AnswerAction action) {
        frame(digest, "actionType", "ANSWER");
        sequence(digest, "statement", action.document().statements().stream()
                .map(AgentActionFingerprint::statementDigestMaterial).toList());
    }

    private static String statementDigestMaterial(AnswerStatement statement) {
        MessageDigest digest = messageDigest();
        frame(digest, "statementId", statement.statementId().value());
        frame(digest, "type", statement.type().name());
        frame(digest, "text", statement.text());
        frame(digest, "claimIdPresent", Boolean.toString(statement.claimId().isPresent()));
        statement.claimId().ifPresent(claimId -> frame(digest, "claimId", claimId.value()));
        sequence(digest, "citation", statement.citations().stream().map(citation -> citation.value()).sorted().toList());
        sequence(digest, "observationId", statement.observationIds().stream()
                .map(observationId -> observationId.value()).sorted().toList());
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void clarify(MessageDigest digest, ClarifyAction action) {
        frame(digest, "actionType", "CLARIFY");
        frame(digest, "question", action.question());
        sequence(digest, "candidateHandle", action.candidates().stream()
                .map(candidate -> candidate.value()).toList());
    }

    private static void sequence(MessageDigest digest, String fieldName, List<String> values) {
        frame(digest, fieldName + "Count", Integer.toString(values.size()));
        for (String value : values) {
            frame(digest, fieldName, value);
        }
    }

    private static void frame(MessageDigest digest, String fieldName, String value) {
        byte[] fieldNameBytes = fieldName.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        length(digest, fieldNameBytes.length);
        digest.update(fieldNameBytes);
        length(digest, valueBytes.length);
        digest.update(valueBytes);
    }

    private static void length(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static MessageDigest messageDigest() {
        try {
            return MessageDigest.getInstance(SHA_256);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK SHA-256 digest is unavailable", exception);
        }
    }
}
