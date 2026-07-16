package com.java.system.agent.ai.tools;

import com.java.system.agent.ai.loop.AgentLoopRunner;
import com.java.system.agent.ai.loop.LoopTrace;
import com.java.system.agent.ai.loop.TerminationReason;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TranslatorToolResultTest {

    @Test
    void from_marksVerified_whenTraceAccepted() {
        LoopTrace trace = new LoopTrace("t1", "translator", "翻譯完成", true, List.of(), List.of());

        TranslatorToolResult result = TranslatorToolResult.from(trace);

        assertThat(result.verified()).isTrue();
        assertThat(result.render()).isEqualTo("verified: true\n翻譯完成");
    }

    @Test
    void from_stripsUnverifiedNote_andMarksUnverified() {
        LoopTrace trace = new LoopTrace("t1", "translator",
                AgentLoopRunner.UNVERIFIED_NOTE + "翻譯完成", false, List.of(), List.of(),
                TerminationReason.TRANSLATOR_TIMEOUT);

        TranslatorToolResult result = TranslatorToolResult.from(trace);

        assertThat(result.verified()).isFalse();
        assertThat(result.render()).isEqualTo("verified: false\n翻譯完成");
        assertThat(result.render()).doesNotContain(AgentLoopRunner.UNVERIFIED_NOTE);
    }
}
