package com.java.system.agent.model.quota;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 從受控的 HTTP 失敗 metadata 提取模型 provider 建議的重試時間
 */
public final class ModelRetryAfterExtractor {

    private static final int MAX_CAUSE_DEPTH = 64;

    public Instant retryAt(Throwable exception, Instant now, Duration fallback) {
        Objects.requireNonNull(exception, "model provider exception must not be null");
        Objects.requireNonNull(now, "model retry base time must not be null");
        Objects.requireNonNull(fallback, "model retry fallback must not be null");
        return retryAfterHeader(exception)
                .flatMap(value -> parseRetryAfter(value, now))
                .orElseGet(() -> now.plus(fallback));
    }

    private Optional<String> retryAfterHeader(Throwable exception) {
        Set<Throwable> inspected = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = exception;
        int inspectedCount = 0;
        while (Objects.nonNull(current) && inspected.add(current) && inspectedCount < MAX_CAUSE_DEPTH) {
            Optional<String> retryAfter = retryAfterHeaderFor(current);
            if (retryAfter.isPresent()) {
                return retryAfter;
            }
            current = current.getCause();
            inspectedCount++;
        }
        return Optional.empty();
    }

    private Optional<String> retryAfterHeaderFor(Throwable exception) {
        if (exception instanceof RestClientResponseException responseException) {
            return retryAfterHeader(responseException.getResponseHeaders());
        }
        if (exception instanceof ResponseStatusException responseException) {
            return retryAfterHeader(responseException.getHeaders());
        }
        return Optional.empty();
    }

    private Optional<String> retryAfterHeader(HttpHeaders headers) {
        if (Objects.isNull(headers)) {
            return Optional.empty();
        }
        return Optional.ofNullable(headers.getFirst(HttpHeaders.RETRY_AFTER));
    }

    private Optional<Instant> parseRetryAfter(String value, Instant now) {
        String normalized = value.trim();
        try {
            long seconds = Long.parseLong(normalized);
            if (seconds < 0) {
                return Optional.empty();
            }
            return Optional.of(now.plusSeconds(seconds));
        } catch (NumberFormatException ignored) {
            return parseHttpDate(normalized, now);
        } catch (ArithmeticException ignored) {
            return Optional.empty();
        }
    }

    private Optional<Instant> parseHttpDate(String value, Instant now) {
        try {
            Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            if (retryAt.isBefore(now)) {
                return Optional.empty();
            }
            return Optional.of(retryAt);
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }
}
