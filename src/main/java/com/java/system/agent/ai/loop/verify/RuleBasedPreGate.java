package com.java.system.agent.ai.loop.verify;

import com.java.system.agent.ai.loop.Candidate;
import com.java.system.agent.ai.loop.LoopState;
import com.java.system.agent.ai.loop.Verdict;
import com.java.system.agent.ai.loop.VerifyGate;
import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/** 免費、確定性的前置把關:擋掉明顯錯誤才花 token 給後面的 LLM 驗證 */
public final class RuleBasedPreGate implements VerifyGate {

    private static final Pattern CODE_TOKEN = Pattern.compile(
            "\\b[A-Z]\\w*(Service|Controller|Repository|DAO|Mapper|Entity)\\b"
                    + "|\\b(com|org|net|io|java)(\\.[a-z][a-zA-Z0-9]*){2,}\\b"
                    + "|\\b(SELECT|INSERT|UPDATE|DELETE|WHERE)\\s");

    @Override
    public Verdict verify(Candidate candidate, LoopState state) {
        if (!StringUtils.hasText(candidate.answer())) {
            return Verdict.revise("回答為空，請根據已知資訊作答");
        }
        if (CODE_TOKEN.matcher(candidate.answer()).find()) {
            return Verdict.revise("輸出疑似包含程式碼或資料表細節，請改以業務語言描述");
        }
        return Verdict.accept();
    }
}
