package com.java.system.agent.answering.domain.run;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Attempt 預算只保留正常規劃與查詢計數的測試
 */
class AttemptBudgetTest {

    @Test
    void consumes_normal_and_query_capacity_independently() {
        AttemptBudget budget = new AttemptBudget(1, 0, 1, 0, 1, 0, 1, 0);

        AttemptBudget query = budget.consumeAgentStep().consumeQueryExecution();

        assertThat(query.usedAgentSteps()).isEqualTo(1);
        assertThat(query.usedQueryExecutions()).isEqualTo(1);
    }

    @Test
    void rejects_overconsumption_of_each_remaining_counter() {
        AttemptBudget budget = new AttemptBudget(1, 0, 1, 0, 1, 0, 1, 0);

        assertThatIllegalArgumentException().isThrownBy(() -> budget.consumeAgentStep().consumeAgentStep());
        assertThatIllegalArgumentException().isThrownBy(() -> budget.consumeQueryExecution().consumeQueryExecution());
        assertThatIllegalArgumentException().isThrownBy(() -> budget.consumeActionRejection().consumeActionRejection());
        assertThatIllegalArgumentException().isThrownBy(() -> budget.consumeRevisionRestart().consumeRevisionRestart());
    }
}
