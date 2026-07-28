package com.java.system.agent.model.quota;

import com.java.system.agent.model.ModelTransportFailureClassifier;
import com.java.system.agent.model.ModelLifecycleMetrics;
import com.java.system.agent.runtime.domain.run.ExecutionDeferral;
import com.java.system.agent.runtime.domain.run.ExecutionDeferralReason;
import com.java.system.agent.runtime.port.out.ExternalExecutionDeferredException;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 在 action 與 verifier 共用模型前保留本機容量並轉譯 provider 限流的同步 gate
 */
public final class ModelQuotaGate implements ChatModel {

    private static final String STREAMING_UNSUPPORTED = "agent model streaming is not supported";

    private final ChatModel provider;
    private final ModelQuotaWindow window;
    private final ModelInputTokenEstimator tokenEstimator;
    private final ModelRetryAfterExtractor retryAfterExtractor;
    private final Clock clock;
    private final Duration providerRetryFallback;
    private final ModelLifecycleMetrics metrics;

    public ModelQuotaGate(
            ChatModel provider,
            ModelQuotaWindow window,
            ModelInputTokenEstimator tokenEstimator,
            ModelRetryAfterExtractor retryAfterExtractor,
            Clock clock,
            Duration providerRetryFallback) {
        this(provider, window, tokenEstimator, retryAfterExtractor, clock, providerRetryFallback, ModelLifecycleMetrics.NO_OP);
    }

    public ModelQuotaGate(
            ChatModel provider,
            ModelQuotaWindow window,
            ModelInputTokenEstimator tokenEstimator,
            ModelRetryAfterExtractor retryAfterExtractor,
            Clock clock,
            Duration providerRetryFallback,
            ModelLifecycleMetrics metrics) {
        this.provider = Objects.requireNonNull(provider, "model provider must not be null");
        this.window = Objects.requireNonNull(window, "model quota window must not be null");
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "model token estimator must not be null");
        this.retryAfterExtractor = Objects.requireNonNull(retryAfterExtractor,
                "model retry extractor must not be null");
        this.clock = Objects.requireNonNull(clock, "model quota clock must not be null");
        this.providerRetryFallback = Objects.requireNonNull(providerRetryFallback,
                "model provider retry fallback must not be null");
        this.metrics = Objects.requireNonNull(metrics, "agent lifecycle metrics must not be null");
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Instant now = clock.instant();
        long estimatedTokens = tokenEstimator.estimate(prompt);
        metrics.requested(estimatedTokens);
        ModelQuotaWindow.Reservation reservation = reserve(now, estimatedTokens);
        ChatResponse response;
        try {
            response = Objects.requireNonNull(provider.call(prompt), "model provider response must not be null");
        } catch (ExternalExecutionDeferredException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (ModelTransportFailureClassifier.isRateLimited(exception)) {
                metrics.providerRateLimited();
                Instant retryAt = retryAfterExtractor.retryAt(exception, now, providerRetryFallback);
                throw new ExternalExecutionDeferredException(
                        new ExecutionDeferral(retryAt, ExecutionDeferralReason.RATE_LIMITED));
            }
            throw exception;
        }
        providerPromptTokens(response).ifPresent(tokens -> window.reconcile(reservation, tokens));
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.error(new UnsupportedOperationException(STREAMING_UNSUPPORTED));
    }

    private ModelQuotaWindow.Reservation reserve(Instant now, long estimatedTokens) {
        ModelQuotaWindow.ReservationResult result = window.reserve(now, estimatedTokens);
        if (result instanceof ModelQuotaWindow.ReservationResult.Accepted accepted) {
            return accepted.reservation();
        }
        ModelQuotaWindow.ReservationResult.Deferred deferred = (ModelQuotaWindow.ReservationResult.Deferred) result;
        throw new ExternalExecutionDeferredException(
                new ExecutionDeferral(deferred.retryAt(), ExecutionDeferralReason.RATE_LIMITED));
    }

    private Optional<Long> providerPromptTokens(ChatResponse response) {
        ChatResponseMetadata metadata = response.getMetadata();
        if (Objects.isNull(metadata)) {
            return Optional.empty();
        }
        Usage usage = metadata.getUsage();
        if (Objects.isNull(usage)) {
            return Optional.empty();
        }
        Integer promptTokens = usage.getPromptTokens();
        if (Objects.isNull(promptTokens) || promptTokens < 1) {
            return Optional.empty();
        }
        return Optional.of(promptTokens.longValue());
    }
}
