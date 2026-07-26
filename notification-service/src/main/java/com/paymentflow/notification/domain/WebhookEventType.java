package com.paymentflow.notification.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The canonical, merchant-facing event vocabulary (M18.2, §4.5) — the public names that
 * appear in a {@link WebhookEvent}, in a webhook body, in M19's Events API, and in every
 * SDK from M22 onward. Deliberately <em>not</em> the platform's internal event-type
 * strings ({@code PaymentAuthorized}, …): those are payment-service's own vocabulary,
 * never a public promise, and D4's schema-per-service rule says a consumer must not
 * adopt a producer's internal names as its own contract.
 *
 * <p>The naming is {@code <resource>.<past-tense verb>} in lower snake_case — the shape
 * every comparable platform converged on, chosen here because it reads correctly in a
 * subscription list ({@code payment.authorized}) and leaves obvious room for resources
 * this platform will add later ({@code refund.*} in M19, and disputes if §13-Q7 is ever
 * answered yes) without renaming anything that already exists.
 *
 * <p>Lives in {@code domain} rather than beside the Kafka payloads because it is a
 * closed vocabulary the same way {@link DeliveryStatus} and {@link AttemptOutcome} are:
 * defined here in M18.2 because the endpoint-management API must validate subscriptions
 * against it, and consumed in M18.3 by {@code WebhookEventFactory}, which performs the
 * internal → canonical translation.
 *
 * <p><b>Adding a value is additive and never breaking</b> (§4.10): a wildcard-subscribed
 * endpoint starts receiving it, and every other endpoint is unaffected. Clients are
 * required to tolerate unknown event types, which is why
 * {@link WebhookSubscription#matches} treats {@code "*"} as matching values that did not
 * exist when the subscription was written.
 */
public enum WebhookEventType {

    PAYMENT_CREATED("payment.created", "PaymentCreated"),
    PAYMENT_AUTHORIZED("payment.authorized", "PaymentAuthorized"),
    PAYMENT_CAPTURED("payment.captured", "PaymentCaptured"),
    PAYMENT_FAILED("payment.failed", "PaymentFailed"),
    PAYMENT_VOIDED("payment.voided", "PaymentVoided"),
    PAYMENT_REFUNDED("payment.refunded", "PaymentRefunded"),
    PAYMENT_PARTIALLY_REFUNDED("payment.partially_refunded", "PaymentPartiallyRefunded");

    private final String canonicalName;
    private final String internalEventType;

    WebhookEventType(String canonicalName, String internalEventType) {
        this.canonicalName = canonicalName;
        this.internalEventType = internalEventType;
    }

    /** The public name, e.g. {@code payment.authorized}. */
    public String canonicalName() {
        return canonicalName;
    }

    /** payment-service's internal {@code EventEnvelope.eventType}, e.g. {@code PaymentAuthorized}. */
    public String internalEventType() {
        return internalEventType;
    }

    /**
     * Translates an internal event type to its canonical form. Empty for an internal
     * event this platform deliberately does not publish to merchants — merchant/API-key
     * lifecycle events on {@code merchant.events}, for instance, are audit's concern, not
     * a webhook. M18.3's factory treats empty as "not a merchant-facing event", never as
     * an error.
     */
    public static Optional<WebhookEventType> fromInternal(String internalEventType) {
        return Arrays.stream(values())
                .filter(type -> type.internalEventType.equals(internalEventType))
                .findFirst();
    }

    /** Parses a canonical name exactly — case-sensitively, since these are contract strings, not user input. */
    public static Optional<WebhookEventType> fromCanonical(String canonicalName) {
        return Arrays.stream(values())
                .filter(type -> type.canonicalName.equals(canonicalName))
                .findFirst();
    }

    /** Every canonical name, for validating a subscription request and for documenting the vocabulary. */
    public static Set<String> canonicalNames() {
        return Arrays.stream(values()).map(WebhookEventType::canonicalName).collect(Collectors.toUnmodifiableSet());
    }

    /** The documented vocabulary as a stable, sorted, comma-separated list — used in validation messages. */
    public static String documentedVocabulary() {
        return Arrays.stream(values())
                .map(WebhookEventType::canonicalName)
                .sorted()
                .collect(Collectors.joining(", "))
                .toLowerCase(Locale.ROOT);
    }
}
