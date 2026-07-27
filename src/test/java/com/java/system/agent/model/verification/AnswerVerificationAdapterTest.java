package com.java.system.agent.model.verification;

import com.java.system.agent.runtime.domain.answer.AnswerDisposition;
import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.answer.AnswerStatement;
import com.java.system.agent.runtime.domain.answer.AnswerVerificationMode;
import com.java.system.agent.runtime.domain.answer.StatementId;
import com.java.system.agent.runtime.domain.answer.StatementType;
import com.java.system.agent.runtime.domain.conversation.SessionHistory;
import com.java.system.agent.runtime.port.out.AnswerVerificationContext;
import com.java.system.agent.runtime.port.out.AnswerVerificationResult;
import com.java.system.agent.runtime.port.out.AnswerVerificationUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spring AI 回答 verifier strategy 與持久化 mode dispatch 邊界測試
 */
class AnswerVerificationAdapterTest {

    @Test
    void mapsLlmAcceptedCompleteWithEveryStatementVerdict() {
        CountingChatModel model = new CountingChatModel("""
                {"disposition":"ACCEPTED_COMPLETE","statementVerdicts":[{"statementId":"statement-1","status":"SUPPORTED","description":"Supported"}],"unaddressedParts":[],"blockingUncertainties":[],"rejectionReasons":[]}
                """);
        SpringAiAnswerVerificationAdapter adapter = new SpringAiAnswerVerificationAdapter(ChatClient.builder(model).build());

        AnswerVerificationResult result = adapter.verify(AnswerVerificationMode.LLM, context());

        assertThat(result).isInstanceOf(AnswerVerificationResult.LlmVerdict.class);
        AnswerVerificationResult.LlmVerdict verdict = (AnswerVerificationResult.LlmVerdict) result;
        assertThat(verdict.verdict().disposition()).isEqualTo(AnswerDisposition.ACCEPTED_COMPLETE);
        assertThat(verdict.verdict().statementVerdicts()).extracting(item -> item.statementId().value())
                .containsExactly("statement-1");
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void mapsInconclusiveAndRejectedDispositionsWithoutChangingTheirDetails() {
        CountingChatModel inconclusiveModel = new CountingChatModel("""
                {"disposition":"ACCEPTED_INCONCLUSIVE","statementVerdicts":[{"statementId":"statement-1","status":"SUPPORTED","description":"Supported"}],"unaddressedParts":["Missing detail"],"blockingUncertainties":["Unknown implementation"],"rejectionReasons":[]}
                """);
        SpringAiAnswerVerificationAdapter inconclusive = new SpringAiAnswerVerificationAdapter(ChatClient.builder(inconclusiveModel).build());
        CountingChatModel rejectedModel = new CountingChatModel("""
                {"disposition":"REJECTED","statementVerdicts":[{"statementId":"statement-1","status":"UNSUPPORTED","description":"Unsupported"}],"unaddressedParts":[],"blockingUncertainties":[],"rejectionReasons":["Citation is absent"]}
                """);
        SpringAiAnswerVerificationAdapter rejected = new SpringAiAnswerVerificationAdapter(ChatClient.builder(rejectedModel).build());

        AnswerVerificationResult.LlmVerdict inconclusiveResult = (AnswerVerificationResult.LlmVerdict) inconclusive.verify(AnswerVerificationMode.LLM, context());
        AnswerVerificationResult.LlmVerdict rejectedResult = (AnswerVerificationResult.LlmVerdict) rejected.verify(AnswerVerificationMode.LLM, context());

        assertThat(inconclusiveResult.verdict().disposition()).isEqualTo(AnswerDisposition.ACCEPTED_INCONCLUSIVE);
        assertThat(inconclusiveResult.verdict().unaddressedParts()).containsExactly("Missing detail");
        assertThat(rejectedResult.verdict().disposition()).isEqualTo(AnswerDisposition.REJECTED);
        assertThat(rejectedResult.verdict().rejectionReasons()).containsExactly("Citation is absent");
        assertThat(inconclusiveModel.calls()).isEqualTo(1);
        assertThat(rejectedModel.calls()).isEqualTo(1);
    }

    @Test
    void treatsMalformedVerifierOutputAsUnavailableNotRejected() {
        CountingChatModel model = new CountingChatModel("""
                {"disposition":"REJECTED","statementVerdicts":[],"unaddressedParts":[],"blockingUncertainties":[],"rejectionReasons":[]}
                """);
        SpringAiAnswerVerificationAdapter adapter = new SpringAiAnswerVerificationAdapter(ChatClient.builder(model).build());

        assertThatThrownBy(() -> adapter.verify(AnswerVerificationMode.LLM, context()))
                .isInstanceOf(AnswerVerificationUnavailableException.class)
                .hasMessage("ANSWER_VERIFIER_UNAVAILABLE");
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void treatsGenericVerifierTransportFailureAsSanitizedUnavailable() {
        CountingChatModel model = new CountingChatModel(new IllegalStateException("provider response omitted"));
        SpringAiAnswerVerificationAdapter adapter = new SpringAiAnswerVerificationAdapter(ChatClient.builder(model).build());

        assertThatThrownBy(() -> adapter.verify(AnswerVerificationMode.LLM, context()))
                .isInstanceOf(AnswerVerificationUnavailableException.class)
                .hasMessage("ANSWER_VERIFIER_UNAVAILABLE");
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void contractOnlyReturnsContractAcceptedWithoutAnyModelCall() {
        ContractOnlyAnswerVerificationAdapter adapter = new ContractOnlyAnswerVerificationAdapter();

        AnswerVerificationResult result = adapter.verify(AnswerVerificationMode.CONTRACT_ONLY, context());

        assertThat(result).isEqualTo(new AnswerVerificationResult.ContractAccepted());
    }

    @Test
    void dispatcherUsesPersistedModeRatherThanAnyCurrentDefault() {
        CountingChatModel model = new CountingChatModel("""
                {"disposition":"ACCEPTED_COMPLETE","statementVerdicts":[{"statementId":"statement-1","status":"SUPPORTED","description":"Supported"}],"unaddressedParts":[],"blockingUncertainties":[],"rejectionReasons":[]}
                """);
        SpringAiAnswerVerificationAdapter llm = new SpringAiAnswerVerificationAdapter(ChatClient.builder(model).build());
        AnswerVerificationDispatcher dispatcher = new AnswerVerificationDispatcher(llm, new ContractOnlyAnswerVerificationAdapter());

        AnswerVerificationResult result = dispatcher.verify(AnswerVerificationMode.CONTRACT_ONLY, context());

        assertThat(result).isEqualTo(new AnswerVerificationResult.ContractAccepted());
        assertThat(model.calls()).isZero();
    }

    @Test
    void classifiesVerifierResourceExhaustionWithoutAnotherCall() {
        CountingChatModel model = new CountingChatModel(new ResourceExhaustedException());
        SpringAiAnswerVerificationAdapter adapter = new SpringAiAnswerVerificationAdapter(ChatClient.builder(model).build());

        assertThatThrownBy(() -> adapter.verify(AnswerVerificationMode.LLM, context()))
                .isInstanceOf(AnswerVerificationUnavailableException.class)
                .hasMessage("RATE_LIMITED");
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void rendersOnlyVerifierContextWithoutActionControlTerms() {
        String prompt = new AnswerVerificationPromptRenderer().render(context(), "response-schema");

        assertThat(prompt).containsSubsequence("Current question", "Session history", "Proposed document", "Available evidence",
                "Available observations", "Cited evidence", "Referenced observations", "Response contract");
        assertThat(prompt.toLowerCase(java.util.Locale.ROOT)).doesNotContain("capability", "candidate", "budget", "action");
        assertThat(AnswerVerificationPromptRenderer.SYSTEM_INSTRUCTION.toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("capability", "candidate", "budget", "action");
    }

    @Test
    void dispatcherRejectsDuplicateMissingExtraAndNullStrategies() {
        AnswerVerificationStrategy llm = new FixedStrategy(AnswerVerificationMode.LLM);
        AnswerVerificationStrategy contractOnly = new FixedStrategy(AnswerVerificationMode.CONTRACT_ONLY);

        assertThatThrownBy(() -> new AnswerVerificationDispatcher(llm, llm)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnswerVerificationDispatcher(llm)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnswerVerificationDispatcher(llm, contractOnly, contractOnly))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnswerVerificationDispatcher(llm, null)).isInstanceOf(NullPointerException.class);
    }

    private AnswerVerificationContext context() {
        AnswerDocument document = new AnswerDocument(List.of(new AnswerStatement(new StatementId("statement-1"),
                StatementType.UNCERTAINTY, "The implementation may differ", Optional.empty(), java.util.Set.of(), java.util.Set.of())));
        return new AnswerVerificationContext("What is known?", SessionHistory.empty(), document,
                List.of(), List.of(), List.of(), List.of());
    }

    private static final class CountingChatModel implements ChatModel {

        private final String response;
        private final Optional<RuntimeException> failure;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingChatModel(String response) {
            this.response = response;
            this.failure = Optional.empty();
        }

        private CountingChatModel(RuntimeException failure) {
            this.response = "";
            this.failure = Optional.of(failure);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            if (failure.isPresent()) {
                throw failure.orElseThrow();
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
        }

        private int calls() {
            return calls.get();
        }
    }

    private static final class ResourceExhaustedException extends RuntimeException {
        private ResourceExhaustedException() {
            super("provider response omitted");
        }
    }

    private record FixedStrategy(AnswerVerificationMode mode) implements AnswerVerificationStrategy {

        @Override
        public AnswerVerificationResult verify(AnswerVerificationMode mode, AnswerVerificationContext context) {
            return new AnswerVerificationResult.ContractAccepted();
        }
    }
}
