package com.java.system.agent.ai.tools;

import com.java.system.agent.ai.loop.LoopTrace;

/** find_call_graph 工具輸出：第一行為 verified 中繼資料行，之後為翻譯內容。 */
public record TranslatorToolResult(boolean verified, String answer) {

    public static TranslatorToolResult from(LoopTrace trace) {
        return new TranslatorToolResult(trace.accepted(), trace.plainFinalAnswer());
    }

    public String render() {
        return "verified: " + verified + "\n" + answer;
    }
}
