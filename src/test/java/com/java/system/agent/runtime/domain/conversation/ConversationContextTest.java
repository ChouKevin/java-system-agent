package com.java.system.agent.runtime.domain.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationContextTest {

    @Test
    @DisplayName("the window keeps the newest ten turns and drops the oldest")
    void keepsTheNewestTenTurns() {
        ConversationContext context = ConversationContext.empty();
        for (int index = 1; index <= 12; index++) {
            context = context.withTurn(
                    new ConversationTurn("問題 " + index, "回答 " + index, Optional.empty()),
                    Optional.empty(), List.of(), Optional.empty());
        }

        assertThat(context.recentTurns()).hasSize(10);
        assertThat(context.recentTurns().get(0).question()).isEqualTo("問題 3");
        assertThat(context.recentTurns().get(9).question()).isEqualTo("問題 12");
    }

    @Test
    @DisplayName("a pending clarification is visible while its turn is retained")
    void reportsAPendingClarification() {
        ConversationContext context = ConversationContext.empty()
                .withTurn(new ConversationTurn("退款怎麼跑", "", Optional.of("你要看哪一個 repository")),
                        Optional.empty(), List.of(), Optional.empty());

        assertThat(context.hasPendingClarification()).isTrue();
        assertThat(ConversationContext.empty().hasPendingClarification()).isFalse();
    }
}
