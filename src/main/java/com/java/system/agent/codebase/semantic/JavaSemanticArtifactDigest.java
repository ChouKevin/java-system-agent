package com.java.system.agent.codebase.semantic;

import com.java.system.agent.runtime.domain.evidence.ArtifactRef;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 將已淨化的證據內容轉成 Agent artifact digest 的純函式
 */
public final class JavaSemanticArtifactDigest {

    private JavaSemanticArtifactDigest() {
    }

    public static ArtifactRef fromContent(String content) {
        Objects.requireNonNull(content, "evidence content must not be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return new ArtifactRef(HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
