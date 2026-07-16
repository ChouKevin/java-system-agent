package com.java.system.agent.slack.model;

import com.java.system.agent.ratelimit.RateLimitKey;
import lombok.Builder;
import lombok.Data;

/** Slack 訊息上下文 */
@Data
@Builder
public class SlackMessageContext {
    private String traceId;
    @RateLimitKey
    private String userId;
    private String teamId;
    private String channelId;
    private String text;
    private String eventId;
    /**
     * 原始訊息或 thread 的 ts
     */
    private String threadTs;
    /**
     * AI 回應的 timestamp，用於更新消息
     */
    private String responseTs;

    /**
     * 將消息封裝在 user mention 中
     */
    public String formatMention(String message) {
        return String.format("<@%s> %s", userId, message);
    }

    public String getInitialAckMessage() {
        return formatMention("已接收您的請求，請稍候...");
    }

    public String getRateLimitMessage(long cooldownSeconds) {
        return formatMention(String.format("請求太頻繁，請稍後再試 (冷卻時間 %d 秒)。", cooldownSeconds));
    }

    /** 串流失敗時由 listener 發送的 fallback 錯誤訊息 */
    public String getStreamFailureMessage() {
        return formatMention("處理您的請求時發生錯誤，請稍後再試。");
    }

    public String getFinalAnalysisMessage(String aiResponse) {
        return formatMention(aiResponse);
    }
}
