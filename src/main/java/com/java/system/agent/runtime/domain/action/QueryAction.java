package com.java.system.agent.runtime.domain.action;

import com.java.system.agent.runtime.domain.handle.CapabilityHandle;
import com.java.system.agent.runtime.domain.handle.CandidateHandle;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 要求 runtime 以既有 capability 與候選項目執行查詢的動作
 */
public record QueryAction(CapabilityHandle capability, List<CandidateHandle> candidates, String questionToResolve,
        Map<String, String> arguments, String rationale) implements AgentAction {
    public QueryAction {
        Objects.requireNonNull(capability, "query action capability must not be null");
        Objects.requireNonNull(candidates, "query action candidates must not be null");
        Objects.requireNonNull(questionToResolve, "query action question must not be null");
        Objects.requireNonNull(arguments, "query action arguments must not be null");
        Objects.requireNonNull(rationale, "query action rationale must not be null");
        if (questionToResolve.isBlank() || rationale.isBlank()) throw new IllegalArgumentException("query action question and rationale must not be blank");
        candidates = List.copyOf(candidates); arguments = Map.copyOf(arguments);
    }
}
