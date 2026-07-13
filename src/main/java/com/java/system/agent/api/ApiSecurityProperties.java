package com.java.system.agent.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * API 寫入保護設定
 * <p>
 * writeToken 為共享密鑰，經 application.yml 由環境變數 API_WRITE_TOKEN 注入
 * 未設定時所有 /git 寫入端點採 fail-closed 一律拒絕
 */
@ConfigurationProperties(prefix = "api")
public record ApiSecurityProperties(@DefaultValue("") String writeToken) {

    /** 是否已設定寫入密鑰 */
    public boolean hasWriteToken() {
        return StringUtils.hasText(writeToken);
    }

    /**
     * 以常數時間比較請求密鑰與設定密鑰，避免 timing attack
     * 密鑰未設定或請求密鑰為空白時一律視為不符
     */
    public boolean matchesWriteToken(String provided) {
        if (!hasWriteToken() || !StringUtils.hasText(provided)) {
            return false;
        }
        return MessageDigest.isEqual(
                writeToken.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    /** 避免設定物件被記錄時洩漏共享密鑰 */
    @Override
    public String toString() {
        String renderedToken = hasWriteToken() ? "<redacted>" : "<not-configured>";
        return "ApiSecurityProperties[writeToken=" + renderedToken + "]";
    }
}
