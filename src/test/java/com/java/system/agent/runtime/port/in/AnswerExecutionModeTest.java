package com.java.system.agent.runtime.port.in;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * AnswerExecutionMode inbox attempt 約束測試
 */
class AnswerExecutionModeTest {

    @Test
    void capacityResumeAcceptsTheInitialAndLaterInboxAttempts() {
        assertThatCode(() -> AnswerExecutionMode.CAPACITY_RESUME.validateAttempt(1)).doesNotThrowAnyException();
        assertThatCode(() -> AnswerExecutionMode.CAPACITY_RESUME.validateAttempt(3)).doesNotThrowAnyException();
    }

    @Test
    void recoveryModesRejectTheInitialInboxAttempt() {
        assertThatIllegalArgumentException().isThrownBy(() -> AnswerExecutionMode.RETRY.validateAttempt(1));
        assertThatIllegalArgumentException().isThrownBy(
                () -> AnswerExecutionMode.TERMINAL_RECONCILIATION.validateAttempt(1));
    }
}
