package com.java.system.agent.runtime.application;

public enum AnalysisBudgetActivity {

    REVISION_PROBE(false),
    SEMANTIC_QUERY(true),
    SEMANTIC_RETRY(true);

    private final boolean consumesSemanticCall;

    AnalysisBudgetActivity(boolean consumesSemanticCall) {
        this.consumesSemanticCall = consumesSemanticCall;
    }

    public boolean consumesSemanticCall() {
        return consumesSemanticCall;
    }
}
