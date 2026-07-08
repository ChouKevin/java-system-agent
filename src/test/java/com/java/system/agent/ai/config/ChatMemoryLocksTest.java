package com.java.system.agent.ai.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatMemoryLocksTest {

    @Test
    void sameConversationKeepsSameLockAfterManyOtherKeys() {
        ChatMemoryLocks locks = new ChatMemoryLocks();
        Object first = locks.lockFor("thread-1");

        for (int index = 0; index < 5_000; index++) {
            locks.lockFor("thread-" + index);
        }

        assertThat(locks.lockFor("thread-1")).isSameAs(first);
    }

    @Test
    void rejectsBlankConversationId() {
        ChatMemoryLocks locks = new ChatMemoryLocks();

        assertThatThrownBy(() -> locks.withConversationLock(" ", () -> { }))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
