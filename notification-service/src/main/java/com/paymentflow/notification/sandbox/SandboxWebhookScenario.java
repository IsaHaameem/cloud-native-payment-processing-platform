package com.paymentflow.notification.sandbox;

/**
 * The two webhook-path simulation scenarios sandbox-service already accepts, validates,
 * and persists (M17.5), and that D131 assigned to <b>M18</b> to actually enact.
 *
 * <p>Deliberately a local copy of the two values notification-service cares about, not an
 * import of sandbox-service's eight-value {@code SimulationScenario} — schema-per-service
 * (D4) applies to enums crossing a service boundary exactly as it applies to event
 * payloads. Any other scenario is none of this service's business.
 */
public enum SandboxWebhookScenario {

    /**
     * Deliver each webhook twice, so a developer can prove their consumer is idempotent
     * on {@code event.id} (§8.3). The duplicate is a genuine second HTTP request with its
     * own signature and its own delivery-log attempt — a merchant who cannot see it in
     * the log would have no way to tell it apart from a platform bug.
     */
    DUPLICATE_WEBHOOKS,

    /**
     * Force delivery to fail regardless of what the endpoint actually returns, so a
     * developer can exercise the retry schedule and their own alerting without having to
     * break their endpoint on purpose (§8.3).
     */
    WEBHOOK_FAILURE
}
