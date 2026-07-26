package com.paymentflow.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Tunables for the M18 webhook subsystem (§4.5).
 *
 * <p>{@code requireHttps} exists because §4.5 specifies HTTPS-only endpoints while every
 * local and test webhook sink in this repository — including V1's own
 * {@code NotificationIntegrationTest} — is an {@code http://localhost:…} JDK
 * {@code HttpServer}. Defaulting it <em>on</em> and relaxing it in the test/local
 * profiles keeps the production posture correct while leaving the platform testable;
 * silently accepting {@code http} everywhere would trade a documented exception for an
 * undocumented one.
 */
@ConfigurationProperties(prefix = "paymentflow.webhooks")
public record WebhookProperties(

        /** Pinned onto each endpoint at registration; M21 (D108) is what gives this more than one value. */
        String apiVersion,

        /** How long a superseded signing secret keeps verifying after a rotation (§4.5's dual-secret window). */
        Duration secretRotationGracePeriod,

        /** Reject {@code http://} endpoint registrations. On everywhere except the test/local profiles. */
        boolean requireHttps,

        /**
         * Upper bound on endpoints per merchant per mode. Fan-out cost is linear in this
         * number for every event (M18's own risk table: "fan-out multiplies load — N
         * endpoints × M events"), so it is bounded at registration time rather than
         * discovered as a delivery-throughput problem later.
         */
        int maxEndpointsPerMode,

        /**
         * Hostnames exempt from the private/loopback range checks (M18.5). Empty in every
         * deployed environment; populated only in local compose and this repository's own
         * tests, where the only reachable sinks are {@code localhost}. Anything listed
         * here is trusted deliberately — the alternative, weakening
         * {@link com.paymentflow.notification.egress.EgressPolicy} itself for local use,
         * would weaken it everywhere.
         */
        List<String> allowedHosts,

        /** Per-attempt connect timeout — a dead endpoint must not hold a delivery worker. */
        Duration connectTimeout,

        /** Per-attempt read timeout. A merchant is expected to 2xx fast and work asynchronously (§9.4). */
        Duration readTimeout,

        /**
         * Hard cap on how much of a response body is read and stored. A hostile endpoint
         * that streams gigabytes must not be able to exhaust memory or fill
         * {@code webhook_delivery_attempts} through us.
         */
        int maxResponseBytes,

        /**
         * Passphrase for the AES-256-GCM key that protects signing secrets at rest
         * (D137). Env var locally, Secrets Manager in AWS — the same handling as the
         * internal-context HMAC secret (D18/D73), and carrying the same known issue: the
         * local default is deliberately insecure and M29 owns replacing it.
         */
        String secretEncryptionKey,

        /** Upper bound on concurrent outbound deliveries (M18.6) — the shared pool one merchant cannot exhaust. */
        int maxConcurrentDeliveries,

        /** Consecutive failures across distinct events before an endpoint is auto-disabled (M18.7, §4.5). */
        int autoDisableAfterConsecutiveFailures,

        /**
         * The explicit retry schedule (M18.7, §4.5): the delay before each attempt after
         * the first. Its size therefore determines the total attempt count.
         */
        List<Duration> retrySchedule) {

    public WebhookProperties {
        allowedHosts = (allowedHosts == null) ? List.of() : List.copyOf(allowedHosts);
        retrySchedule = (retrySchedule == null) ? List.of() : List.copyOf(retrySchedule);
    }

    /** Total delivery attempts: the immediate one, plus one per scheduled retry. */
    public int maxAttempts() {
        return retrySchedule.size() + 1;
    }
}
