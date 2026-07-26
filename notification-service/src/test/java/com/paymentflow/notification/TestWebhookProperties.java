package com.paymentflow.notification;

import com.paymentflow.notification.config.WebhookProperties;

import java.time.Duration;
import java.util.List;

/**
 * Sensible {@link WebhookProperties} for unit tests, with the one or two fields a given
 * test actually cares about overridable.
 *
 * <p>Exists because the record grew a field in three consecutive sub-milestones and each
 * time broke every unrelated test that happened to construct one. Centralising the
 * argument list means a future field is one edit here rather than one per call site —
 * and, more usefully, means no test carries a long list of values it does not care about,
 * which is what makes the values it *does* care about invisible.
 */
public final class TestWebhookProperties {

    public static final String API_VERSION = "2026-08-01";

    private TestWebhookProperties() {
    }

    public static WebhookProperties defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean requireHttps = true;
        private List<String> allowedHosts = List.of();
        private int maxEndpointsPerMode = 16;
        private int maxResponseBytes = 8192;
        private List<Duration> retrySchedule =
                List.of(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2));
        private int autoDisableAfterConsecutiveFailures = 20;

        public Builder requireHttps(boolean value) {
            this.requireHttps = value;
            return this;
        }

        public Builder allowedHosts(List<String> value) {
            this.allowedHosts = value;
            return this;
        }

        public Builder maxEndpointsPerMode(int value) {
            this.maxEndpointsPerMode = value;
            return this;
        }

        public Builder maxResponseBytes(int value) {
            this.maxResponseBytes = value;
            return this;
        }

        public Builder retrySchedule(List<Duration> value) {
            this.retrySchedule = value;
            return this;
        }

        public Builder autoDisableAfterConsecutiveFailures(int value) {
            this.autoDisableAfterConsecutiveFailures = value;
            return this;
        }

        public WebhookProperties build() {
            return new WebhookProperties(API_VERSION, Duration.ofHours(48), requireHttps, maxEndpointsPerMode,
                    allowedHosts, Duration.ofSeconds(3), Duration.ofSeconds(5), maxResponseBytes,
                    "test-only-webhook-secret-encryption-key", 6, autoDisableAfterConsecutiveFailures,
                    retrySchedule);
        }
    }
}
