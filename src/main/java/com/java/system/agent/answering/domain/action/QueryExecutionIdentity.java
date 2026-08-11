package com.java.system.agent.answering.domain.action;

import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.handle.CandidateHandleRef;

import java.util.List;
import java.util.Objects;

/**
 * 忽略規劃文字後可唯一識別一次 QUERY 外部執行的不可變值
 */
public record QueryExecutionIdentity(
        CapabilityHandle capability,
        List<CandidateHandleRef> candidates,
        CapabilityInputPayload payload) {

    public QueryExecutionIdentity {
        Objects.requireNonNull(capability, "query execution capability must not be null");
        Objects.requireNonNull(candidates, "query execution candidates must not be null");
        Objects.requireNonNull(payload, "query execution payload must not be null");
        candidates = List.copyOf(candidates);
    }

    /**
     * 從模型 QUERY 動作保留實際交付 executor 的欄位
     */
    public static QueryExecutionIdentity from(QueryAction action) {
        Objects.requireNonNull(action, "query action must not be null");
        return new QueryExecutionIdentity(action.capability(), action.candidates(), action.payload());
    }
}
