package com.java.system.agent.model.verification;

import com.java.system.agent.model.ModelTransportFailureClassifier;
import com.java.system.agent.model.prompt.PromptResourceCatalog;
import com.java.system.agent.model.verification.dto.AnswerVerdictResponse;
import com.java.system.agent.answering.domain.answer.AnswerVerificationMode;
import com.java.system.agent.answering.port.out.AnswerVerificationContext;
import com.java.system.agent.answering.port.out.AnswerVerificationResult;
import com.java.system.agent.answering.port.out.AnswerVerificationUnavailableException;
import com.java.system.agent.answering.port.out.ExternalExecutionDeferredException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 以無狀態 Spring AI client 執行獨立回答驗證的 LLM verifier 策略
 */
public final class SpringAiAnswerVerificationAdapter implements AnswerVerificationStrategy {

    private static final String RATE_LIMITED = "RATE_LIMITED";
    private static final String UNAVAILABLE = "ANSWER_VERIFIER_UNAVAILABLE";
    private static final Logger LOGGER = Logger.getLogger(SpringAiAnswerVerificationAdapter.class.getName());

    private final ChatClient chatClient;
    private final PromptResourceCatalog promptCatalog;
    private final BeanOutputConverter<AnswerVerdictResponse> converter;
    private final AnswerVerificationPromptRenderer promptRenderer;
    private final AnswerVerdictResponseInterpreter interpreter;

    public SpringAiAnswerVerificationAdapter(
            ChatClient chatClient,
            PromptResourceCatalog promptCatalog,
            AnswerVerificationPromptRenderer promptRenderer,
            AnswerVerdictResponseInterpreter interpreter) {
        this.chatClient = Objects.requireNonNull(chatClient, "chat client must not be null");
        this.promptCatalog = Objects.requireNonNull(promptCatalog, "prompt resource catalog must not be null");
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
        long startedNanos = System.nanoTime();
        String resultCategory = "CONTRACT_EXCEPTION";
        String content;
        try {
            try {
                content = chatClient.prompt().system(promptCatalog.verificationSystemInstruction())
                        .user(promptRenderer.render(context, converter.getFormat())).call().content();
            } catch (ExternalExecutionDeferredException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                resultCategory = category(exception);
                throw new AnswerVerificationUnavailableException(resultCategory, exception);
            }
            try {
                AnswerVerificationResult result = new AnswerVerificationResult.LlmVerdict(
                        interpreter.interpret(converter.convert(content), context));
                resultCategory = "LLM_VERDICT";
                return result;
            } catch (ExternalExecutionDeferredException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                resultCategory = UNAVAILABLE;
                throw new AnswerVerificationUnavailableException(UNAVAILABLE, exception);
            }
        } finally {
            logOperation(resultCategory, startedNanos, promptCatalog.catalogDigest());
        }
    }

    private static void logOperation(String resultCategory, long startedNanos, String catalogSha256) {
        Level level = "LLM_VERDICT".equals(resultCategory) ? Level.INFO : Level.WARNING;
        LOGGER.log(level, "answer verifier operation=VERIFY resultCategory={0} elapsedMs={1} catalogSha256={2}",
                new Object[]{resultCategory, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos),
                        catalogSha256});
    }

    private static String category(RuntimeException exception) {
        return ModelTransportFailureClassifier.category(exception, RATE_LIMITED, UNAVAILABLE);
    }
}
