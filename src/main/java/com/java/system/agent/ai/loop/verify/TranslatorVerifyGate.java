package com.java.system.agent.ai.loop.verify;

import com.java.system.agent.ai.loop.Candidate;
import com.java.system.agent.ai.loop.LoopState;
import com.java.system.agent.ai.loop.Verdict;
import com.java.system.agent.ai.loop.VerifyGate;
import org.springframework.util.StringUtils;

import java.util.Objects;

/** 翻譯結果把關:未解析/低信心的邊必須展開或標示不確定性 */
public final class TranslatorVerifyGate implements VerifyGate {

    private final String callGraphJson;

    public TranslatorVerifyGate(String callGraphJson) {
        this.callGraphJson = Objects.toString(callGraphJson, "");
    }

    @Override
    public Verdict verify(Candidate candidate, LoopState state) {
        if (hasUncertainEdge() && !acknowledgesUncertainty(candidate)) {
            return Verdict.revise("尚有未解析或低信心的呼叫，請展開或明確標示不確定性");
        }
        return Verdict.accept();
    }

    private boolean hasUncertainEdge() {
        return callGraphJson.contains("\"resolutionStrategy\":\"UNRESOLVED\"")
                || callGraphJson.contains("\"confidence\":\"LOW\"");
    }

    private boolean acknowledgesUncertainty(Candidate candidate) {
        String answer = Objects.toString(candidate.answer(), "");
        return StringUtils.hasText(answer)
                && (answer.contains("不確定")
                || answer.contains("無法確認")
                || answer.contains("可能"));
    }
}
