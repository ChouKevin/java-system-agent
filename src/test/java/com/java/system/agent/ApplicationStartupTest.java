package com.java.system.agent;

import com.java.system.agent.codebase.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.inbox.application.SessionInboxProcessor;
import com.java.system.agent.runtime.port.in.AnswerQuestionUseCase;
import com.java.system.agent.runtime.port.out.AgentActionPort;
import com.java.system.agent.runtime.port.out.AnswerVerificationPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"spring.profiles.active=dev", "spring.ai.model.chat=none"})
class ApplicationStartupTest {

    @Test
    @DisplayName("the application context loads")
    void should_load_application_context() {
    }

    @Test
    @DisplayName("inactive development profile should not create chat memory beans")
    void should_not_create_chat_memory_beans(@Autowired ApplicationContext applicationContext) {
        assertThat(applicationContext.getBeansOfType(ChatMemory.class)).hasSize(0);
        assertThat(applicationContext.getBeansOfType(ChatMemoryRepository.class)).hasSize(0);
        assertThat(applicationContext.getBeansOfType(AnswerQuestionUseCase.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(SessionInboxProcessor.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(AgentActionPort.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(AnswerVerificationPort.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(JavaSemanticServiceHttpAdapter.class)).isEmpty();
    }
}
