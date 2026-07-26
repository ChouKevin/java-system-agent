package com.java.system.agent.runtime.domain.run;

/**
 * 一次 Attempt 的 agent、semantic、拒絕與重啟額度
 */
public record AttemptBudget(int maxAgentSteps, int usedAgentSteps, int maxSemanticQueries, int usedSemanticQueries,
        int maxActionRejections, int usedActionRejections, int maxRevisionRestarts, int usedRevisionRestarts,
        int finalAnswerReserve, int usedFinalAnswers) {
    public AttemptBudget {
        validate(maxAgentSteps, usedAgentSteps, "agent steps");
        validate(maxSemanticQueries, usedSemanticQueries, "semantic queries");
        validate(maxActionRejections, usedActionRejections, "action rejections");
        validate(maxRevisionRestarts, usedRevisionRestarts, "revision restarts");
        validate(finalAnswerReserve, usedFinalAnswers, "final answers");
    }
    public AttemptBudget consumeAgentStep() { return copy(consume(maxAgentSteps, usedAgentSteps, "agent step"), usedSemanticQueries, usedActionRejections, usedRevisionRestarts, usedFinalAnswers); }
    public AttemptBudget consumeSemanticQuery() { return copy(usedAgentSteps, consume(maxSemanticQueries, usedSemanticQueries, "semantic query"), usedActionRejections, usedRevisionRestarts, usedFinalAnswers); }
    public AttemptBudget consumeActionRejection() { return copy(usedAgentSteps, usedSemanticQueries, consume(maxActionRejections, usedActionRejections, "action rejection"), usedRevisionRestarts, usedFinalAnswers); }
    public AttemptBudget consumeRevisionRestart() { return copy(usedAgentSteps, usedSemanticQueries, usedActionRejections, consume(maxRevisionRestarts, usedRevisionRestarts, "revision restart"), usedFinalAnswers); }
    public AttemptBudget consumeFinalAnswer() { return copy(usedAgentSteps, usedSemanticQueries, usedActionRejections, usedRevisionRestarts, consume(finalAnswerReserve, usedFinalAnswers, "final answer")); }
    public boolean hasAgentStepRemaining() { return usedAgentSteps < maxAgentSteps; }
    public boolean hasSemanticQueryRemaining() { return usedSemanticQueries < maxSemanticQueries; }
    public boolean hasActionRejectionRemaining() { return usedActionRejections < maxActionRejections; }
    public boolean hasRevisionRestartRemaining() { return usedRevisionRestarts < maxRevisionRestarts; }
    public boolean hasFinalAnswerRemaining() { return usedFinalAnswers < finalAnswerReserve; }
    private AttemptBudget copy(int steps, int queries, int rejections, int restarts, int finals) { return new AttemptBudget(maxAgentSteps, steps, maxSemanticQueries, queries, maxActionRejections, rejections, maxRevisionRestarts, restarts, finalAnswerReserve, finals); }
    private static int consume(int maximum, int used, String name) { if (used >= maximum) throw new IllegalArgumentException(name + " budget is exhausted"); return used + 1; }
    private static void validate(int maximum, int used, String name) { if (maximum < 1 || used < 0 || used > maximum) throw new IllegalArgumentException(name + " budget is invalid"); }
}
