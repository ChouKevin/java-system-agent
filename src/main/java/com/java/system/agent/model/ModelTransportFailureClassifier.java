package com.java.system.agent.model;

import com.google.genai.errors.ApiException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 以受控 HTTP metadata 與 provider exception 類別分類模型 transport 失敗
 */
public final class ModelTransportFailureClassifier {

    private static final int MAX_CAUSE_DEPTH = 64;

    private ModelTransportFailureClassifier() {
    }

    /**
     * 不讀取 provider body 或 exception message 的固定分類
     */
    public static String category(RuntimeException exception, String rateLimited, String unavailable) {
        Objects.requireNonNull(exception, "model transport exception must not be null");
        Objects.requireNonNull(rateLimited, "rate limited category must not be null");
        Objects.requireNonNull(unavailable, "unavailable category must not be null");
        return isRateLimited(exception) ? rateLimited : unavailable;
    }

    public static boolean isRateLimited(Throwable exception) {
        Objects.requireNonNull(exception, "model transport failure must not be null");
        Set<Throwable> inspected = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = exception;
        int inspectedCount = 0;
        while (Objects.nonNull(current) && inspected.add(current) && inspectedCount < MAX_CAUSE_DEPTH) {
            if (isDirectlyRateLimited(current)) {
                return true;
            }
            current = current.getCause();
            inspectedCount++;
        }
        return false;
    }

    private static boolean isDirectlyRateLimited(Throwable exception) {
        if (exception instanceof ApiException apiException && apiException.code() == 429) {
            return true;
        }
        if (exception instanceof RestClientResponseException responseException
                && responseException.getStatusCode().value() == 429) {
            return true;
        }
        if (exception instanceof ResponseStatusException responseException
                && responseException.getStatusCode().value() == 429) {
            return true;
        }
        String exceptionType = exception.getClass().getSimpleName().toUpperCase(Locale.ROOT).replace("_", "");
        return exceptionType.contains("RESOURCEEXHAUSTED");
    }
}
