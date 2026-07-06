package com.java.system.agent.ai.loop.verify;

import com.java.system.agent.ai.loop.Candidate;
import com.java.system.agent.ai.loop.LoopState;
import com.java.system.agent.ai.loop.Verdict;
import com.java.system.agent.ai.loop.VerifyGate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 用一次 ChatModel 呼叫判斷候選答案:自評與獨立 critic 共用，靠 prompt 區分 */
@Slf4j
public final class LlmVerifier implements VerifyGate {

    private static final Pattern VERDICT = Pattern.compile(
            "VERDICT:\\s*(PASS|REVISE)(?:\\s*[—:-]\\s*(.*))?",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final ChatModel chatModel;
    private final String promptTemplate;
    private final String label;

    public LlmVerifier(ChatModel chatModel, String promptTemplate, String label) {
        this.chatModel = chatModel;
        this.promptTemplate = promptTemplate;
        this.label = label;
    }

    @Override
    public Verdict verify(Candidate candidate, LoopState state) {
        String reply;
        try {
            reply = chatModel.call(promptTemplate.formatted(candidate.answer(), state.query()));
        } catch (Exception e) {
            log.warn("[{}] verifier failed, accepting to avoid blocking", label, e);
            return Verdict.accept();
        }
        if (!StringUtils.hasText(reply)) {
            return Verdict.accept();
        }
        Matcher matcher = VERDICT.matcher(reply);
        if (!matcher.find()) {
            log.debug("[{}] verifier reply missing marker, accepting: {}", label, reply);
            return Verdict.accept();
        }
        if ("PASS".equalsIgnoreCase(matcher.group(1))) {
            return Verdict.accept();
        }
        String reason = StringUtils.hasText(matcher.group(2)) ? matcher.group(2).strip() : "需要修正";
        return Verdict.revise("[" + label + "] " + reason);
    }
}
