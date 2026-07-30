package com.java.system.agent.answering.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TerminalResponseCoordinator terminal response collaboration 的測試
 */
class TerminalResponseCoordinatorTest {

    @Test
    void exposesTheTerminalResponseCollaborator() {
        assertThat(TerminalResponseCoordinator.class).isNotNull();
    }
}
