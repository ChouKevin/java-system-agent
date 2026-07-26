package com.java.system.agent.runtime.application.validation;

import com.java.system.agent.runtime.domain.action.AgentAction;
import com.java.system.agent.runtime.domain.action.ClarifyAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ActionValidationTest {

    @Test
    void should_require_an_original_action_for_accepted_results() {
        assertThatNullPointerException().isThrownBy(() -> new ActionValidation.Accepted(null));
    }

    @Test
    void should_require_a_complete_rejected_result_shape() {
        AgentAction action = action();

        assertThatNullPointerException().isThrownBy(() -> new ActionValidation.Rejected(null, "rejected", action));
        assertThatNullPointerException().isThrownBy(() -> new ActionValidation.Rejected(
                ActionRejectionCode.UNKNOWN_ACTION, null, action));
        assertThatNullPointerException().isThrownBy(() -> new ActionValidation.Rejected(
                ActionRejectionCode.UNKNOWN_ACTION, "rejected", null));
        assertThatIllegalArgumentException().isThrownBy(() -> new ActionValidation.Rejected(
                ActionRejectionCode.UNKNOWN_ACTION, " ", action));
    }

    private static AgentAction action() {
        return new ClarifyAction("Which repository?", List.of(), "Need repository scope");
    }
}
