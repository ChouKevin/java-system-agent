package com.java.system.agent.runtime.domain.run;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AttemptBudgetTest {

    @Test
    void should_keep_final_answer_reserve_separate_from_normal_and_query_capacity() {
        AttemptBudget budget = new AttemptBudget(1, 0, 1, 0, 1, 0, 1, 0, 1, 0);

        AttemptBudget normalStep = budget.consumeAgentStep();
        AttemptBudget query = normalStep.consumeQueryExecution();
        AttemptBudget finalResponse = budget.consumeFinalAnswer();

        assertThat(query.usedFinalAnswers()).isZero();
        assertThat(finalResponse.usedFinalAnswers()).isEqualTo(1);
        assertThatIllegalArgumentException().isThrownBy(finalResponse::consumeFinalAnswer);
    }

    @Test
    void should_reject_overconsumption_of_each_independent_counter() {
        AttemptBudget budget = new AttemptBudget(1, 0, 1, 0, 1, 0, 1, 0, 1, 0);

        assertThatIllegalArgumentException().isThrownBy(() -> budget.consumeAgentStep().consumeAgentStep());
        assertThatIllegalArgumentException().isThrownBy(() -> budget.consumeQueryExecution().consumeQueryExecution());
        assertThatIllegalArgumentException().isThrownBy(() -> budget.consumeActionRejection().consumeActionRejection());
        assertThatIllegalArgumentException().isThrownBy(() -> budget.consumeRevisionRestart().consumeRevisionRestart());
    }
}
