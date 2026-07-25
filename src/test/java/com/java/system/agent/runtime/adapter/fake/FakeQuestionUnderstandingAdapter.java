package com.java.system.agent.runtime.adapter.fake;

import com.java.system.agent.runtime.port.out.QuestionUnderstanding;
import com.java.system.agent.runtime.port.out.QuestionUnderstandingPort;
import com.java.system.agent.runtime.port.out.RepositoryDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link QuestionUnderstandingPort} 的測試替身，回傳固定的理解結果並記錄每次呼叫的引數
 */
public final class FakeQuestionUnderstandingAdapter implements QuestionUnderstandingPort {

    private final QuestionUnderstanding scriptedUnderstanding;
    private final List<Invocation> invocations = new ArrayList<>();

    public FakeQuestionUnderstandingAdapter(QuestionUnderstanding scriptedUnderstanding) {
        this.scriptedUnderstanding = Objects.requireNonNull(
                scriptedUnderstanding, "scripted question understanding must not be null");
    }

    @Override
    public synchronized QuestionUnderstanding understand(String question, List<RepositoryDescriptor> candidates) {
        Objects.requireNonNull(question, "question must not be null");
        Objects.requireNonNull(candidates, "repository candidates must not be null");
        invocations.add(new Invocation(question, candidates));
        return scriptedUnderstanding;
    }

    public synchronized List<Invocation> invocations() {
        return List.copyOf(invocations);
    }

    public record Invocation(String question, List<RepositoryDescriptor> candidates) {

        public Invocation {
            Objects.requireNonNull(question, "question must not be null");
            Objects.requireNonNull(candidates, "repository candidates must not be null");
            candidates = List.copyOf(candidates);
        }
    }
}
