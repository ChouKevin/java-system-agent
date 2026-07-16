package com.java.system.agent.slack.client;

import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * 串流失敗事件，攜帶失敗原因與可能的底層例外
 * 由 SlackStreamClient 產生，交給呼叫端決定如何善後
 */
public record SlackStreamFailure(String reason, Throwable cause) {

    /** 建立串流啟動失敗事件，detail 為空時以 unknown 代替 */
    public static SlackStreamFailure startFailure(String detail) {
        String normalized = StringUtils.hasText(detail) ? detail : "unknown";
        return new SlackStreamFailure("stream start failed: " + normalized, null);
    }

    /** 建立串流過程失敗事件，包裝訂閱時收到的終止錯誤 */
    public static SlackStreamFailure streamingFailure(Throwable cause) {
        Objects.requireNonNull(cause, "cause must not be null");
        return new SlackStreamFailure("streaming error: " + cause, cause);
    }
}
