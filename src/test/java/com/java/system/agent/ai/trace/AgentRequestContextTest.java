package com.java.system.agent.ai.trace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRequestContextTest {

    @Test
    void create_copiesCallerSuppliedSlackCorrelation() {
        AgentRequestContext context = AgentRequestContext.create(
                "trace-1", "U1", "T1", "C1", "E1", "123.456", "問題");

        assertThat(context.traceId()).isEqualTo("trace-1");
        assertThat(context.userId()).isEqualTo("U1");
        assertThat(context.teamId()).isEqualTo("T1");
        assertThat(context.channelId()).isEqualTo("C1");
        assertThat(context.eventId()).isEqualTo("E1");
        assertThat(context.conversationId()).isEqualTo("123.456");
        assertThat(context.userQuery()).isEqualTo("問題");
        assertThat(context.startedAt()).isNotNull();
    }
}
