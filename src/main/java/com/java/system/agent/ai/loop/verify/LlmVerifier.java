package com.java.system.agent.ai.loop.verify;

import com.java.system.agent.ai.loop.Candidate;
import com.java.system.agent.ai.loop.LlmRateLimiter;
import com.java.system.agent.ai.loop.LoopState;
import com.java.system.agent.ai.loop.RateLimitReservation;
import com.java.system.agent.ai.loop.Verdict;
import com.java.system.agent.ai.loop.VerifyGate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用一次 ChatModel 呼叫判斷候選答案:自評與獨立 critic 共用，靠 prompt 區分。
 * fail-closed:驗證不可用或回覆無效時一律 REVISE，避免未驗證答案直接放行。
 */
@Slf4j
public final class LlmVerifier implements VerifyGate {

    private static final Pattern VERDICT = Pattern.compile(
            "^VERDICT:\\s*(PASS|REVISE)(?:\\s*[—:-]\\s*(.*))?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ANSWER_OPEN_TAG = Pattern.compile("<\\s*answer\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANSWER_CLOSE_TAG = Pattern.compile("<\\s*/\\s*answer\\s*>", Pattern.CASE_INSENSITIVE);

    private static final String UNAVAILABLE_CRITIQUE = "驗證程序未完成，請確認回答符合輸出規則後重新提交";

    private final ChatModel chatModel;
    private final String promptTemplate;
    private final String label;
    private final LlmRateLimiter rateLimiter;

    public LlmVerifier(ChatModel chatModel, String promptTemplate, String label) {
        this(chatModel, promptTemplate, label, LlmRateLimiter.NOOP);
    }

    public LlmVerifier(ChatModel chatModel, String promptTemplate, String label, LlmRateLimiter rateLimiter) {
        this.chatModel = chatModel;
        this.promptTemplate = promptTemplate;
        this.label = label;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public Verdict verify(Candidate candidate, LoopState state) {
        String reply;
        try {
            String prompt = promptTemplate.formatted(safePromptData(candidate.answer()), safePromptData(state.query()));
            RateLimitReservation reservation = rateLimiter.acquire(prompt);
            reply = chatModel.call(prompt);
            rateLimiter.record(reservation, reply);
        } catch (Exception e) {
            log.warn("[{}] verifier failed, revising to stay fail-closed", label, e);
            return unavailable();
        }
        if (!StringUtils.hasText(reply)) {
            return unavailable();
        }
        Matcher matcher = lastLineMatcher(reply);
        if (matcher == null) {
            log.debug("[{}] verifier reply missing final-line marker, revising: {}", label, reply);
            return unavailable();
        }
        if ("PASS".equalsIgnoreCase(matcher.group(1))) {
            return Verdict.accept();
        }
        String reason = StringUtils.hasText(matcher.group(2)) ? matcher.group(2).strip() : "需要修正";
        return Verdict.revise("[" + label + "] " + reason);
    }

    /** 只認回覆最後一個非空行的 marker，被審查文字中引用的 marker 一律視為資料。 */
    private Matcher lastLineMatcher(String reply) {
        List<String> lines = reply.strip().lines()
                .map(String::strip)
                .filter(StringUtils::hasText)
                .toList();
        if (lines.isEmpty()) {
            return null;
        }
        Matcher matcher = VERDICT.matcher(lines.getLast());
        return matcher.matches() ? matcher : null;
    }

    private Verdict unavailable() {
        return Verdict.revise("[" + label + "] " + UNAVAILABLE_CRITIQUE);
    }

    private String safePromptData(String value) {
        if (value == null) {
            return "";
        }
        String safe = ANSWER_CLOSE_TAG.matcher(value).replaceAll("＜/answer＞");
        return ANSWER_OPEN_TAG.matcher(safe).replaceAll("＜answer＞");
    }
}
