package com.java.system.agent.runtime.application.state;

/**
 * 分類會消耗 attempt 預算的活動，並標記其中哪些同時消耗語意呼叫預算
 *
 * <p>{@code REVISION_PROBE} 只消耗 step 預算，不消耗語意呼叫預算；
 * {@code SEMANTIC_QUERY} 與 {@code SEMANTIC_RETRY} 兩者都消耗——{@link
 * DefaultStateReducer} 套用 {@code BudgetConsumed} 事件時，就是靠
 * {@link #consumesSemanticCall()} 決定要不要額外扣語意呼叫預算</p>
 */
public enum BudgetedActivity {

    REVISION_PROBE(false),
    SEMANTIC_QUERY(true),
    SEMANTIC_RETRY(true);

    private final boolean consumesSemanticCall;

    BudgetedActivity(boolean consumesSemanticCall) {
        this.consumesSemanticCall = consumesSemanticCall;
    }

    public boolean consumesSemanticCall() {
        return consumesSemanticCall;
    }
}
