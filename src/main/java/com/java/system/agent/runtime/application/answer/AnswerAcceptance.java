package com.java.system.agent.runtime.application.answer;

import com.java.system.agent.runtime.domain.answer.Answer;
import com.java.system.agent.runtime.domain.need.InformationNeedId;
import com.java.system.agent.runtime.domain.run.RunOutcome;

import java.util.Objects;
import java.util.Set;

/**
 * {@link AnswerAcceptancePolicy#accept} 的回傳值：定案的 Answer、依覆蓋率決定的最終 outcome，
 * 以及仍未被覆蓋到的必要 need
 *
 * <p>{@code uncoveredRequiredNeeds} 非空時，outcome 只會在 innerOutcome 為 COMPLETED 的情況下
 * 被降級為 INCONCLUSIVE；其餘 innerOutcome 一律原樣傳遞</p>
 */
public record AnswerAcceptance(Answer answer, RunOutcome outcome, Set<InformationNeedId> uncoveredRequiredNeeds) {

    public AnswerAcceptance {
        Objects.requireNonNull(answer, "answer must not be null");
        Objects.requireNonNull(outcome, "run outcome must not be null");
        Objects.requireNonNull(uncoveredRequiredNeeds, "uncovered required needs must not be null");
        uncoveredRequiredNeeds = Set.copyOf(uncoveredRequiredNeeds);
    }
}
