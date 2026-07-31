package com.java.system.agent.answering.port.out;

/**
 * HTTP mutation port 的 typed 結果
 */
public sealed interface HttpMutationResult permits HttpMutationResult.NotImplemented {

    /**
     * 表示 runtime 尚未提供 HTTP mutation adapter
     */
    record NotImplemented() implements HttpMutationResult {
    }
}
