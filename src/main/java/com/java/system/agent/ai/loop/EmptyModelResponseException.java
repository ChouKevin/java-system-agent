package com.java.system.agent.ai.loop;

/** 模型回應缺少任何 generation（例如被安全過濾攔截）時丟出。 */
public class EmptyModelResponseException extends RuntimeException {

    public EmptyModelResponseException(String message) {
        super(message);
    }
}
