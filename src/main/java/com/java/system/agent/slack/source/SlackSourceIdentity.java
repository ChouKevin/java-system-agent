package com.java.system.agent.slack.source;

import org.springframework.util.StringUtils;

/**
 * Slack 訊息與 thread 的可逆 transport 身分
 */
public record SlackSourceIdentity(String workspace, String channel, String messageTimestamp, String rootTimestamp) {

    public SlackSourceIdentity {
        workspace = required(workspace, "workspace");
        channel = required(channel, "channel");
        messageTimestamp = required(messageTimestamp, "message timestamp");
        rootTimestamp = required(rootTimestamp, "root timestamp");
    }

    /**
     * 從 Slack 事件欄位建立 root-aware 身分
     */
    public static SlackSourceIdentity fromEvent(String workspace, String channel, String messageTimestamp, String threadTimestamp) {
        String rootTimestamp = StringUtils.hasText(threadTimestamp) ? threadTimestamp : messageTimestamp;
        return new SlackSourceIdentity(workspace, channel, messageTimestamp, rootTimestamp);
    }

    /**
     * 產生 delivery 可解碼的 session 身分
     */
    public SlackSourceIdentity sessionIdentity() {
        return new SlackSourceIdentity(workspace, channel, rootTimestamp, rootTimestamp);
    }

    private static String required(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
