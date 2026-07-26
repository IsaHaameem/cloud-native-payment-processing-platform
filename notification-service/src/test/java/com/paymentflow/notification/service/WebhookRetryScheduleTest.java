package com.paymentflow.notification.service;

import com.paymentflow.notification.TestWebhookProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retry schedule's arithmetic (M18.7). A pure function of the attempt count and the
 * configured table, so the boundary that matters most — the transition from "retry
 * again" to "dead-letter" — is provable without a broker, a database, or a clock.
 */
class WebhookRetryScheduleTest {

    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");

    /** The documented production schedule: 8 attempts over ~24h (§4.5). */
    private static final List<Duration> PRODUCTION_SCHEDULE = List.of(
            Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10),
            Duration.ofHours(1), Duration.ofHours(6), Duration.ofHours(12));

    private static WebhookRetrySchedule schedule(List<Duration> delays) {
        return new WebhookRetrySchedule(TestWebhookProperties.builder().retrySchedule(delays).build());
    }

    @Test
    void theDocumentedScheduleIsEightAttemptsSpanningRoughlyADay() {
        WebhookRetrySchedule schedule = schedule(PRODUCTION_SCHEDULE);

        // The published promise, asserted rather than described: a merchant reading the
        // docs must get the same numbers the platform actually uses.
        assertThat(schedule.maxAttempts()).isEqualTo(8);
        // 5s + 30s + 2m + 10m + 1h + 6h + 12h = 19h 12m 35s — inside §4.5's "~24h", with
        // room for the final attempt's own timeout.
        Duration total = PRODUCTION_SCHEDULE.stream().reduce(Duration.ZERO, Duration::plus);
        assertThat(total).isEqualTo(Duration.ofHours(19).plusMinutes(12).plusSeconds(35));
        assertThat(total).isBetween(Duration.ofHours(19), Duration.ofHours(24));
    }

    @Test
    void eachCompletedAttemptSelectsTheNextDelayInOrder() {
        WebhookRetrySchedule schedule = schedule(PRODUCTION_SCHEDULE);

        assertThat(schedule.delayBefore(1)).contains(Duration.ofSeconds(5));
        assertThat(schedule.delayBefore(2)).contains(Duration.ofSeconds(30));
        assertThat(schedule.delayBefore(3)).contains(Duration.ofMinutes(2));
        assertThat(schedule.delayBefore(4)).contains(Duration.ofMinutes(10));
        assertThat(schedule.delayBefore(5)).contains(Duration.ofHours(1));
        assertThat(schedule.delayBefore(6)).contains(Duration.ofHours(6));
        assertThat(schedule.delayBefore(7)).contains(Duration.ofHours(12));
    }

    @Test
    void theEighthCompletedAttemptExhaustsTheScheduleAndDeadLetters() {
        WebhookRetrySchedule schedule = schedule(PRODUCTION_SCHEDULE);

        // Attempt 7 done → one retry left. Attempt 8 done → nothing left. Off-by-one here
        // is the difference between 8 attempts and 9, or between dead-lettering a delivery
        // that still had a retry owed to it.
        assertThat(schedule.delayBefore(7)).isPresent();
        assertThat(schedule.delayBefore(8)).isEmpty();
        assertThat(schedule.nextAttemptAt(8, NOW)).isEmpty();
        assertThat(schedule.isExhausted(7)).isFalse();
        assertThat(schedule.isExhausted(8)).isTrue();
        assertThat(schedule.isExhausted(9)).isTrue();
    }

    @Test
    void theNextAttemptInstantIsTheDelayAddedToNow() {
        WebhookRetrySchedule schedule = schedule(PRODUCTION_SCHEDULE);

        assertThat(schedule.nextAttemptAt(1, NOW)).contains(NOW.plusSeconds(5));
        assertThat(schedule.nextAttemptAt(5, NOW)).contains(NOW.plus(Duration.ofHours(1)));
    }

    @Test
    void anEmptyScheduleMeansTheFirstFailureDeadLettersImmediately() {
        WebhookRetrySchedule schedule = schedule(List.of());

        // A legitimate configuration ("deliver once, never retry") that must not produce
        // an infinite loop or a negative index.
        assertThat(schedule.maxAttempts()).isEqualTo(1);
        assertThat(schedule.delayBefore(1)).isEmpty();
        assertThat(schedule.isExhausted(1)).isTrue();
    }

    @Test
    void anImpossibleAttemptCountIsHandledRatherThanThrowing() {
        WebhookRetrySchedule schedule = schedule(PRODUCTION_SCHEDULE);

        // Defensive: a corrupted counter must not take down a delivery worker with an
        // IndexOutOfBoundsException.
        assertThat(schedule.delayBefore(0)).isEmpty();
        assertThat(schedule.delayBefore(-1)).isEmpty();
        assertThat(schedule.delayBefore(9999)).isEmpty();
    }
}
