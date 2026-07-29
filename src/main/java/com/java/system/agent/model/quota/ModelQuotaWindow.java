package com.java.system.agent.model.quota;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 在單一程序內同步保留模型 request 與 input token 容量的滾動視窗
 */
public final class ModelQuotaWindow {

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final ZoneId PACIFIC_ZONE = ZoneId.of("America/Los_Angeles");

    private final int requestsPerMinute;
    private final int inputTokensPerMinute;
    private final int requestsPerDay;
    private final List<MinuteEntry> minuteEntries = new ArrayList<>();
    private final Map<UUID, MinuteEntry> reservations = new HashMap<>();
    private LocalDate dailyDate = LocalDate.MIN;
    private int dailyRequests;
    private long minuteTokens;

    public ModelQuotaWindow(int requestsPerMinute, int inputTokensPerMinute, int requestsPerDay) {
        if (requestsPerMinute < 1 || inputTokensPerMinute < 1 || requestsPerDay < 1) {
            throw new IllegalArgumentException("model quota limits must be positive");
        }
        this.requestsPerMinute = requestsPerMinute;
        this.inputTokensPerMinute = inputTokensPerMinute;
        this.requestsPerDay = requestsPerDay;
    }

    public synchronized ReservationResult reserve(Instant now, long estimatedTokens) {
        Objects.requireNonNull(now, "model quota reservation time must not be null");
        if (estimatedTokens < 1) {
            throw new IllegalArgumentException("estimated model input tokens must be positive");
        }
        purgeExpired(now);
        resetDailyWindow(now);

        if (dailyRequests >= requestsPerDay) {
            return new ReservationResult.Deferred(nextPacificMidnight(now));
        }

        Optional<Instant> retryAt = minuteRetryAt(now, estimatedTokens);
        if (retryAt.isPresent()) {
            return new ReservationResult.Deferred(retryAt.orElseThrow());
        }

        UUID reservationId = UUID.randomUUID();
        MinuteEntry entry = new MinuteEntry(reservationId, now, estimatedTokens);
        minuteEntries.add(entry);
        reservations.put(reservationId, entry);
        minuteTokens = Math.addExact(minuteTokens, estimatedTokens);
        dailyRequests++;
        return new ReservationResult.Accepted(new Reservation(reservationId));
    }

    public synchronized void reconcile(Reservation reservation, long providerPromptTokens) {
        Objects.requireNonNull(reservation, "model quota reservation must not be null");
        if (providerPromptTokens < 1) {
            return;
        }
        MinuteEntry entry = reservations.get(reservation.id());
        if (Objects.isNull(entry)) {
            return;
        }
        minuteTokens = Math.addExact(Math.subtractExact(minuteTokens, entry.tokens()), providerPromptTokens);
        entry.replaceTokens(providerPromptTokens);
    }

    private Optional<Instant> minuteRetryAt(Instant now, long estimatedTokens) {
        List<Instant> retryInstants = new ArrayList<>();
        if (minuteEntries.size() >= requestsPerMinute) {
            retryInstants.add(oldestExpiry());
        }
        if (!minuteEntries.isEmpty() && Math.addExact(minuteTokens, estimatedTokens) > inputTokensPerMinute) {
            retryInstants.add(tokenCapacityRetryAt(now, estimatedTokens));
        }
        return retryInstants.stream().max(Comparator.naturalOrder());
    }

    private Instant tokenCapacityRetryAt(Instant now, long estimatedTokens) {
        long remainingTokens = Math.addExact(minuteTokens, estimatedTokens);
        List<MinuteEntry> orderedEntries = minuteEntries.stream()
                .sorted(Comparator.comparing(minuteEntry -> minuteEntry.expiresAt()))
                .toList();
        Instant retryAt = now.plus(MINUTE);
        for (MinuteEntry entry : orderedEntries) {
            remainingTokens = Math.subtractExact(remainingTokens, entry.tokens());
            retryAt = entry.expiresAt();
            if (remainingTokens <= inputTokensPerMinute) {
                return retryAt;
            }
        }
        return retryAt;
    }

    private Instant oldestExpiry() {
        return minuteEntries.stream()
                .map(minuteEntry -> minuteEntry.expiresAt())
                .min(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalStateException("model quota minute window unexpectedly empty"));
    }

    private void purgeExpired(Instant now) {
        List<MinuteEntry> expiredEntries = minuteEntries.stream()
                .filter(entry -> !entry.expiresAt().isAfter(now))
                .toList();
        for (MinuteEntry entry : expiredEntries) {
            minuteEntries.remove(entry);
            reservations.remove(entry.id());
            minuteTokens = Math.subtractExact(minuteTokens, entry.tokens());
        }
    }

    private void resetDailyWindow(Instant now) {
        LocalDate pacificDate = now.atZone(PACIFIC_ZONE).toLocalDate();
        if (!dailyDate.equals(pacificDate)) {
            dailyDate = pacificDate;
            dailyRequests = 0;
        }
    }

    private Instant nextPacificMidnight(Instant now) {
        return now.atZone(PACIFIC_ZONE).toLocalDate().plusDays(1).atStartOfDay(PACIFIC_ZONE).toInstant();
    }

    public record Reservation(UUID id) {

        public Reservation {
            Objects.requireNonNull(id, "model quota reservation id must not be null");
        }
    }

    public sealed interface ReservationResult permits ReservationResult.Accepted, ReservationResult.Deferred {

        record Accepted(Reservation reservation) implements ReservationResult {

            public Accepted {
                Objects.requireNonNull(reservation, "model quota accepted reservation must not be null");
            }
        }

        record Deferred(Instant retryAt) implements ReservationResult {

            public Deferred {
                Objects.requireNonNull(retryAt, "model quota retry time must not be null");
            }
        }
    }

    /**
     * 一筆尚在滾動分鐘視窗內的本機容量保留
     */
    private static final class MinuteEntry {

        private final UUID id;
        private final Instant reservedAt;
        private long tokens;

        private MinuteEntry(UUID id, Instant reservedAt, long tokens) {
            this.id = id;
            this.reservedAt = reservedAt;
            this.tokens = tokens;
        }

        private UUID id() {
            return id;
        }

        private long tokens() {
            return tokens;
        }

        private Instant expiresAt() {
            return reservedAt.plus(MINUTE);
        }

        private void replaceTokens(long replacementTokens) {
            tokens = replacementTokens;
        }
    }
}
