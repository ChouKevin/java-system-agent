package com.java.system.agent.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LruChatMemoryRepositoryTest {

    @Test
    void evictsLeastRecentlyUsedConversationBeyondCap() {
        LruChatMemoryRepository repository = new LruChatMemoryRepository(2);

        repository.saveAll("c1", List.of(new UserMessage("m1")));
        repository.saveAll("c2", List.of(new UserMessage("m2")));
        repository.saveAll("c3", List.of(new UserMessage("m3")));

        assertThat(repository.findConversationIds())
                .doesNotContain("c1")
                .containsExactly("c2", "c3");
    }

    @Test
    void refreshesRecencyOnRead() {
        LruChatMemoryRepository repository = new LruChatMemoryRepository(2);

        repository.saveAll("c1", List.of(new UserMessage("m1")));
        repository.saveAll("c2", List.of(new UserMessage("m2")));
        repository.findByConversationId("c1");
        repository.saveAll("c3", List.of(new UserMessage("m3")));

        assertThat(repository.findConversationIds())
                .containsExactly("c1", "c3")
                .doesNotContain("c2");
    }

    @Test
    void returnsEmptyListForUnknownConversation() {
        LruChatMemoryRepository repository = new LruChatMemoryRepository(2);

        assertThat(repository.findByConversationId("missing")).isEmpty();
    }

    @Test
    void rejectsInvalidConversationIds() {
        LruChatMemoryRepository repository = new LruChatMemoryRepository(2);

        assertThatThrownBy(() -> repository.findByConversationId(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.saveAll("", List.of(new UserMessage("m"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.deleteByConversationId(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullMessages() {
        LruChatMemoryRepository repository = new LruChatMemoryRepository(2);

        assertThatThrownBy(() -> repository.saveAll("c1", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.saveAll("c1", java.util.Arrays.asList(new UserMessage("m"), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void returnsDefensiveMessageCopies() {
        LruChatMemoryRepository repository = new LruChatMemoryRepository(2);
        List<org.springframework.ai.chat.messages.Message> messages = new java.util.ArrayList<>(
                List.of(new UserMessage("m1")));

        repository.saveAll("c1", messages);
        messages.clear();

        assertThat(repository.findByConversationId("c1")).hasSize(1);
    }
}
