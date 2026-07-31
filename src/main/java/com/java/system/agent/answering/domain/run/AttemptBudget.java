package com.java.system.agent.answering.domain.run;

/**
 * 一次 Attempt 的 agent、query、execute、拒絕與重啟額度
 */
public record AttemptBudget(int maxAgentSteps, int usedAgentSteps, int maxQueryExecutions, int usedQueryExecutions,
        int maxExecuteExecutions, int usedExecuteExecutions, int maxActionRejections, int usedActionRejections,
        int maxRevisionRestarts, int usedRevisionRestarts) {
    public AttemptBudget {
        validate(maxAgentSteps, usedAgentSteps, "agent steps");
        validate(maxQueryExecutions, usedQueryExecutions, "query executions");
        validate(maxExecuteExecutions, usedExecuteExecutions, "execute executions");
        validate(maxActionRejections, usedActionRejections, "action rejections");
        validate(maxRevisionRestarts, usedRevisionRestarts, "revision restarts");
    }

    public AttemptBudget consumeAgentStep() {
        return copy(consume(maxAgentSteps, usedAgentSteps, "agent step"), usedQueryExecutions,
                usedExecuteExecutions, usedActionRejections, usedRevisionRestarts);
    }

    public AttemptBudget consumeQueryExecution() {
        return copy(usedAgentSteps, consume(maxQueryExecutions, usedQueryExecutions, "query execution"),
                usedExecuteExecutions, usedActionRejections, usedRevisionRestarts);
    }

    public AttemptBudget consumeExecuteExecution() {
        return copy(usedAgentSteps, usedQueryExecutions,
                consume(maxExecuteExecutions, usedExecuteExecutions, "execute execution"), usedActionRejections,
                usedRevisionRestarts);
    }

    public AttemptBudget consumeActionRejection() {
        return copy(usedAgentSteps, usedQueryExecutions, usedExecuteExecutions,
                consume(maxActionRejections, usedActionRejections, "action rejection"), usedRevisionRestarts);
    }

    public AttemptBudget consumeRevisionRestart() {
        return copy(usedAgentSteps, usedQueryExecutions, usedExecuteExecutions, usedActionRejections,
                consume(maxRevisionRestarts, usedRevisionRestarts, "revision restart"));
    }

    public boolean hasAgentStepRemaining() { return usedAgentSteps < maxAgentSteps; }
    public boolean hasQueryExecutionRemaining() { return usedQueryExecutions < maxQueryExecutions; }
    public boolean hasExecuteExecutionRemaining() { return usedExecuteExecutions < maxExecuteExecutions; }
    public boolean hasActionRejectionRemaining() { return usedActionRejections < maxActionRejections; }
    public boolean hasRevisionRestartRemaining() { return usedRevisionRestarts < maxRevisionRestarts; }

    private AttemptBudget copy(int steps, int queries, int executions, int rejections, int restarts) {
        return new AttemptBudget(maxAgentSteps, steps, maxQueryExecutions, queries, maxExecuteExecutions, executions,
                maxActionRejections, rejections, maxRevisionRestarts, restarts);
    }

    private static int consume(int maximum, int used, String name) { if (used >= maximum) throw new IllegalArgumentException(name + " budget is exhausted"); return used + 1; }
    private static void validate(int maximum, int used, String name) { if (maximum < 1 || used < 0 || used > maximum) throw new IllegalArgumentException(name + " budget is invalid"); }
}
