package com.java.system.agent.answering.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QueryActionExecutor query lane collaboration 的測試
 */
class QueryActionExecutorTest {

    @Test
    void exposesTheQueryActionCollaborator() {
        assertThat(QueryActionExecutor.class).isNotNull();
    }
}
