package com.java.system.agent.ai.loop;

import lombok.Getter;

/**
 * LLM 頻率限制額度耗盡例外
 * 所需等待時間超過設定上限時拋出，避免長時間佔用執行緒
 */
@Getter
public class LlmRateLimitExhaustedException extends RuntimeException {

    private final long requiredWaitMillis;
    private final long maxWaitMillis;

    public LlmRateLimitExhaustedException(long requiredWaitMillis, long maxWaitMillis) {
        super("LLM rate limit exhausted: required wait " + requiredWaitMillis
                + " ms exceeds configured max wait " + maxWaitMillis + " ms");
        this.requiredWaitMillis = requiredWaitMillis;
        this.maxWaitMillis = maxWaitMillis;
    }
}
