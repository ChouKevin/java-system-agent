package com.java.system.agent.ai.trace;

import com.java.system.agent.ai.loop.LoopStep;
import com.java.system.agent.ai.loop.LoopTrace;
import com.java.system.agent.ai.loop.StepMetrics;
import com.java.system.agent.ai.loop.TerminationReason;
import com.java.system.agent.ai.loop.Verdict;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TraceRecordMapperTest {

    private final TraceRecordMapper mapper = new TraceRecordMapper();

    @Test
    void map_capturesCorrelationMemoryRejectedCandidateAndMetrics() {
        AgentRequestContext context = new AgentRequestContext(
                "trace-1", "U1", "T1", "C1", "E1", "123.456", "這次問題",
                Instant.parse("2026-07-15T01:23:04Z"));
        LoopTrace childTrace = new LoopTrace(
                "child-1", "translator", "翻譯草稿", false,
                List.of(new LoopStep(0, "翻譯", List.of(), Verdict.revise("需修正"),
                        List.of(), new StepMetrics(20L, 3, 2), "翻譯草稿")),
                List.of(), TerminationReason.TRANSLATOR_TIMEOUT);
        LoopTrace loopTrace = new LoopTrace(
                "trace-1", "analyst", "被拒草稿", false,
                List.of(
                        new LoopStep(0, "初稿", List.of(), Verdict.revise("證據不足"),
                                List.of(childTrace), new StepMetrics(30L, 10, 4), "第一稿"),
                        new LoopStep(1, "修正版", List.of(), Verdict.revise("仍不足"),
                                List.of(), new StepMetrics(50L, 20, 6), "被拒草稿")),
                List.of(), TerminationReason.MAX_TURNS);
        Instant completedAt = Instant.parse("2026-07-15T01:23:08Z");

        AgentTraceRecord record = mapper.map(
                context,
                List.of(new UserMessage("上一題"), new AssistantMessage("上一答")),
                loopTrace,
                "回答未通過驗證，請稍後再試。追蹤編號：trace-1",
                completedAt);

        assertThat(record.createdAt()).isEqualTo(context.startedAt());
        assertThat(record.completedAt()).isEqualTo(completedAt);
        assertThat(record.traceId()).isEqualTo("trace-1");
        assertThat(record.conversationId()).isEqualTo("123.456");
        assertThat(record.userId()).isEqualTo("U1");
        assertThat(record.teamId()).isEqualTo("T1");
        assertThat(record.channelId()).isEqualTo("C1");
        assertThat(record.eventId()).isEqualTo("E1");
        assertThat(record.userQuery()).isEqualTo("這次問題");
        assertThat(record.payload().memorySnapshot())
                .containsExactly(
                        new MemoryMessageSnapshot("USER", "上一題"),
                        new MemoryMessageSnapshot("ASSISTANT", "上一答"));
        assertThat(record.payload().loopTrace()).isSameAs(loopTrace);
        assertThat(record.finalCandidate()).isEqualTo("被拒草稿");
        assertThat(record.slackResponse()).contains("trace-1");
        assertThat(record.accepted()).isFalse();
        assertThat(record.terminationReason()).isEqualTo(TerminationReason.MAX_TURNS);
        assertThat(record.iterationCount()).isEqualTo(2);
        assertThat(record.rejectionCount()).isEqualTo(2L);
        assertThat(record.promptTokens()).isEqualTo(33L);
        assertThat(record.completionTokens()).isEqualTo(12L);
        assertThat(record.durationMillis()).isEqualTo(4_000L);
    }

    @Test
    void map_usesEmptyCandidateWhenNoStepHasCandidate() {
        AgentRequestContext context = new AgentRequestContext(
                "trace-2", "", "", "", "", "thread-2", "問題",
                Instant.parse("2026-07-15T01:23:04Z"));
        LoopTrace loopTrace = new LoopTrace(
                "trace-2", "analyst", "", false,
                List.of(new LoopStep(0, "失敗", List.of(), null)),
                List.of(), TerminationReason.STEP_ERROR);

        AgentTraceRecord record = mapper.map(
                context, List.of(), loopTrace, "安全回覆",
                Instant.parse("2026-07-15T01:23:03Z"));

        assertThat(record.finalCandidate()).isEmpty();
        assertThat(record.durationMillis()).isZero();
    }
}
