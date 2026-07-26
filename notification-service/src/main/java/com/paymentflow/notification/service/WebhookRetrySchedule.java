package com.paymentflow.notification.service;

import com.paymentflow.notification.config.WebhookProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The explicit retry schedule (M18.7, §4.5) — roughly 0s, 5s, 30s, 2m, 10m, 1h, 6h, 12h:
 * eight attempts spread over about a day.
 *
 * <p>A fixed table rather than the jittered exponential backoff V1 used (D46). Two
 * reasons, both merchant-facing rather than technical: the schedule is <b>published</b>,
 * so an integrator can reason about when a delivery will next arrive and how long they
 * have to fix an endpoint before it dead-letters — which is impossible to state honestly
 * about a randomised backoff; and the durations are chosen to cover the failure modes
 * that actually occur (a momentary blip, a deploy, a short outage, a long outage) rather
 * than to be a smooth curve.
 *
 * <p>Jitter is deliberately absent for the same reason. V1's jitter existed to spread
 * retries of *one* topic's backlog; here, deliveries are already spread across endpoints
 * by the dispatcher's partition keying, and a schedule that a merchant cannot predict is
 * worth less than the thundering-herd protection jitter would buy at this scale. If M28
 * measures a herd problem, jitter is a bounded addition to this one class.
 *
 * <p>A pure function of the attempt count and the configured table, so the arithmetic —
 * including the boundary where retries are exhausted — is exhaustively unit-testable
 * without a broker, a database, or a clock.
 */
@Component
public class WebhookRetrySchedule {

    private final WebhookProperties properties;

    public WebhookRetrySchedule(WebhookProperties properties) {
        this.properties = properties;
    }

    /**
     * When the attempt after {@code completedAttempts} should run, or empty when the
     * schedule is exhausted and the delivery must be dead-lettered.
     *
     * @param completedAttempts how many attempts have already been made (never zero when
     *                          called: the first attempt is immediate and unscheduled)
     */
    public Optional<Instant> nextAttemptAt(int completedAttempts, Instant now) {
        return delayBefore(completedAttempts).map(now::plus);
    }

    /** The delay before attempt number {@code completedAttempts + 1}; empty once exhausted. */
    public Optional<Duration> delayBefore(int completedAttempts) {
        // completedAttempts == 1 means the immediate attempt is done, so the first
        // scheduled delay (index 0) applies.
        int index = completedAttempts - 1;
        if (index < 0 || index >= properties.retrySchedule().size()) {
            return Optional.empty();
        }
        return Optional.of(properties.retrySchedule().get(index));
    }

    /** Total attempts a delivery gets: the immediate one plus every scheduled retry. */
    public int maxAttempts() {
        return properties.maxAttempts();
    }

    public boolean isExhausted(int completedAttempts) {
        return completedAttempts >= maxAttempts();
    }
}
