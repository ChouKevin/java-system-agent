package com.java.system.agent.ai.loop.verify;

import com.java.system.agent.ai.loop.Candidate;
import com.java.system.agent.ai.loop.LoopRequest;
import com.java.system.agent.ai.loop.LoopState;
import com.java.system.agent.ai.loop.LoopStep;
import com.java.system.agent.ai.loop.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedPreGateTest {

    private final RuleBasedPreGate gate = new RuleBasedPreGate();

    @Test
    void rejectsCodeLeak() {
        LoopState state = LoopState.init(new LoopRequest("t", "這段流程如何運作?"))
                .recordStep(new LoopStep(0, "p", List.of("find_call_graph"), Verdict.accept()));

        assertThat(gate.verify(new Candidate("系統呼叫 BonusService 進行處理"), state).accepted()).isFalse();
    }

    @Test
    void rejectsBlankCandidate() {
        LoopState state = LoopState.init(new LoopRequest("t", "獎金的計算規則是什麼?"));

        assertThat(gate.verify(new Candidate("   "), state).accepted()).isFalse();
    }

    @Test
    void acceptsCleanAnswerWithEvidence() {
        LoopState state = LoopState.init(new LoopRequest("t", "獎金的計算規則是什麼?"))
                .recordStep(new LoopStep(0, "p", List.of("find_call_graph"), Verdict.accept()));

        assertThat(gate.verify(new Candidate("系統依會員等級與消費金額給予對應回饋"), state).accepted()).isTrue();
    }

    @Test
    void plainEnglishAndUrl_isAccepted() {
        LoopState state = LoopState.init(new LoopRequest("t", "q"));

        assertThat(gate.verify(new Candidate("詳見 www.example.com 的說明"), state).accepted()).isTrue();
        assertThat(gate.verify(new Candidate("系統會在資料 update 之後通知會員"), state).accepted()).isTrue();
        assertThat(gate.verify(new Candidate("這是一個 microservice 架構的行為"), state).accepted()).isTrue();
    }

    @Test
    void codeAndSqlTokens_areStillRejected() {
        LoopState state = LoopState.init(new LoopRequest("t", "q"));

        assertThat(gate.verify(new Candidate("由 BonusService 處理"), state).accepted()).isFalse();
        assertThat(gate.verify(new Candidate("邏輯在 com.java.bonus.service 裡"), state).accepted()).isFalse();
        assertThat(gate.verify(new Candidate("執行 SELECT * FROM bonus"), state).accepted()).isFalse();
    }
}
