package com.java.system.agent.model.verification;

import com.java.system.agent.model.ModelTransportFailureClassifier;
import com.java.system.agent.model.verification.dto.AnswerVerdictResponse;
import com.java.system.agent.runtime.domain.answer.AnswerVerificationMode;
import com.java.system.agent.runtime.port.out.AnswerVerificationContext;
import com.java.system.agent.runtime.port.out.AnswerVerificationResult;
import com.java.system.agent.runtime.port.out.AnswerVerificationUnavailableException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.Objects;

/**
 * 以無狀態 Spring AI client 執行獨立回答驗證的 LLM verifier 策略
 */
public final class SpringAiAnswerVerificationAdapter implements AnswerVerificationStrategy {

    private static final String RATE_LIMITED = "RATE_LIMITED";
    private static final String UNAVAILABLE = "ANSWER_VERIFIER_UNAVAILABLE";

    private final ChatClient chatClient;
    private final BeanOutputConverter<AnswerVerdictResponse> converter;
    private final AnswerVerificationPromptRenderer promptRenderer;
    private final AnswerVerdictResponseInterpreter interpreter;

    public SpringAiAnswerVerificationAdapter(ChatClient chatClient) {
        this(chatClient, new AnswerVerificationPromptRenderer(), new AnswerVerdictResponseInterpreter());
    }

    SpringAiAnswerVerificationAdapter(ChatClient chatClient, AnswerVerificationPromptRenderer promptRenderer,
                                      AnswerVerdictResponseInterpreter interpreter) {
        this.chatClient = Objects.requireNonNull(chatClient, "chat client must not be null");
        this.promptRenderer = Objects.requireNonNull(promptRenderer, "answer verification prompt renderer must not be null");
        this.interpreter = Objects.requireNonNull(interpreter, "answer verdict response interpreter must not be null");
        this.converter = new BeanOutputConverter<>(AnswerVerdictResponse.class);
    }

    @Override
    public AnswerVerificationMode mode() {
        return AnswerVerificationMode.LLM;
    }

    @Override
    public AnswerVerificationResult verify(AnswerVerificationMode mode, AnswerVerificationContext context) {
        Objects.requireNonNull(mode, "answer verification mode must not be null");
        Objects.requireNonNull(context, "answer verification context must not be null");
        if (mode != AnswerVerificationMode.LLM) {
            throw new IllegalArgumentException("LLM verifier received a different mode");
        }
        String content;
        try {
            content = chatClient.prompt().system(AnswerVerificationPromptRenderer.SYSTEM_INSTRUCTION)
                    .user(promptRenderer.render(context, converter.getFormat())).call().content();
        } catch (RuntimeException exception) {
            throw new AnswerVerificationUnavailableException(category(exception), exception);
        }
        try {
            return new AnswerVerificationResult.LlmVerdict(interpreter.interpret(converter.convert(content), context));
        } catch (RuntimeException exception) {
            throw new AnswerVerificationUnavailableException(UNAVAILABLE, exception);
        }
    }

    private static String category(RuntimeException exception) {
        return ModelTransportFailureClassifier.category(exception, RATE_LIMITED, UNAVAILABLE);
    }
}
