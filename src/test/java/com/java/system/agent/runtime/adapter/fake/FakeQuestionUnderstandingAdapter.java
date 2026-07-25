package com.java.system.agent.runtime.adapter.fake;

import com.java.system.agent.runtime.domain.conversation.ConversationContext;
import com.java.system.agent.runtime.port.out.QuestionUnderstanding;
import com.java.system.agent.runtime.port.out.QuestionUnderstandingPort;
import com.java.system.agent.runtime.port.out.RepositoryDescriptor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * {@link QuestionUnderstandingPort} 的測試替身，依註冊順序依序回傳理解結果並記錄每次呼叫的引數
 *
 * <p>理解結果序列耗盡後固定回傳最後一筆，讓呼叫端不需要為每一次可能的呼叫都預先註冊，
 * 與 {@link FakeAnswerCompositionAdapter} 對草稿序列的處理方式一致</p>
 */
public final class FakeQuestionUnderstandingAdapter implements QuestionUnderstandingPort {

    private final Deque<QuestionUnderstanding> scriptedUnderstandings;
    private final List<Invocation> invocations = new ArrayList<>();

    public FakeQuestionUnderstandingAdapter(QuestionUnderstanding... scriptedUnderstandings) {
        Objects.requireNonNull(scriptedUnderstandings, "scripted question understandings must not be null");
        if (scriptedUnderstandings.length == 0) {
            throw new IllegalArgumentException("at least one scripted question understanding is required");
        }
        this.scriptedUnderstandings = new ArrayDeque<>(List.of(scriptedUnderstandings));
    }

    @Override
    public synchronized QuestionUnderstanding understand(
            String question, List<RepositoryDescriptor> candidates, ConversationContext context) {
        Objects.requireNonNull(question, "question must not be null");
        Objects.requireNonNull(candidates, "repository candidates must not be null");
        Objects.requireNonNull(context, "conversation context must not be null");
        invocations.add(new Invocation(question, candidates, context));
        if (scriptedUnderstandings.size() > 1) {
            return scriptedUnderstandings.removeFirst();
        }
        return scriptedUnderstandings.peekFirst();
    }

    public synchronized List<Invocation> invocations() {
        return List.copyOf(invocations);
    }

    public record Invocation(String question, List<RepositoryDescriptor> candidates, ConversationContext context) {

        public Invocation {
            Objects.requireNonNull(question, "question must not be null");
            Objects.requireNonNull(candidates, "repository candidates must not be null");
            Objects.requireNonNull(context, "conversation context must not be null");
            candidates = List.copyOf(candidates);
        }
    }
}
