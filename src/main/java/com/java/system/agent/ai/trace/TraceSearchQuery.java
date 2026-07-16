package com.java.system.agent.ai.trace;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record TraceSearchQuery(
        Optional<String> userId,
        Optional<String> eventId,
        Optional<Boolean> accepted,
        Optional<Instant> from,
        Optional<Instant> to,
        int limit) {

    public TraceSearchQuery {
        userId = Objects.requireNonNull(userId, "userId must not be null");
        eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        accepted = Objects.requireNonNull(accepted, "accepted must not be null");
        from = Objects.requireNonNull(from, "from must not be null");
        to = Objects.requireNonNull(to, "to must not be null");
        Assert.isTrue(limit > 0 && limit <= 100, "limit must be between 1 and 100");
        if (from.isPresent() && to.isPresent()) {
            Assert.isTrue(from.orElseThrow().isBefore(to.orElseThrow()), "from must be before to");
        }
    }

    public static TraceSearchQuery of(
            String userId,
            String eventId,
            Boolean accepted,
            Instant from,
            Instant to,
            int limit) {
        return new TraceSearchQuery(
                optionalText(userId),
                optionalText(eventId),
                Optional.ofNullable(accepted),
                Optional.ofNullable(from),
                Optional.ofNullable(to),
                limit);
    }

    private static Optional<String> optionalText(String value) {
        return StringUtils.hasText(value) ? Optional.of(value) : Optional.empty();
    }
}
