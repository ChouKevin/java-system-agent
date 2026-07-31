package com.java.system.agent.answering.domain.run;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Attempt 預算分別保留正常規劃、查詢與 execute 計數的測試
 */
class AttemptBudgetTest {

    @Test
    void consumes_normal_and_query_capacity_independently() {
        AttemptBudget budget = new AttemptBudget(1, 0, 1, 0, 1, 0, 1, 0, 1, 0);

        AttemptBudget query = budget.consumeAgentStep().consumeQueryExecution();

        assertThat(query.usedAgentSteps()).isEqualTo(1);
        assertThat(query.usedQueryExecutions()).isEqualTo(1);
    }

    @Test
    void rejects_overconsumption_of_each_remaining_counter() {
        AttemptBudget budget = new AttemptBudget(1, 0, 1, 0, 1, 0, 1, 0, 1, 0);

        assertThatIllegalArgumentException().isThrownBy(() -> budget.consumeAgentStep().consumeAgentStep());
        assertThatIllegalArgumentException().isThrownBy(() -> budget.consumeQueryExecution().consumeQueryExecution());
        assertThatIllegalArgumentException().isThrownBy(() -> budget.consumeActionRejection().consumeActionRejection());
        assertThatIllegalArgumentException().isThrownBy(() -> budget.consumeRevisionRestart().consumeRevisionRestart());
    }

    @Test
    void consumes_execute_capacity_without_affecting_query_or_agent_capacity() {
        AttemptBudget initial = new AttemptBudget(6, 0, 5, 0, 1, 0, 3, 0, 1, 0);

        AttemptBudget consumed = initial.consumeExecuteExecution();

        assertThat(consumed.usedExecuteExecutions()).isEqualTo(1);
        assertThat(consumed.hasExecuteExecutionRemaining()).isFalse();
        assertThat(consumed.usedQueryExecutions()).isZero();
        assertThat(consumed.hasAgentStepRemaining()).isTrue();
    }
}
