package com.paymentflow.notification.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Subscription matching is a pure function and the sole determinant of who receives
 * what, so it is tested exhaustively here rather than only through fan-out (M18.6) —
 * the same discipline {@code ApiKeyFormat} and {@code DecisionEngine} are held to.
 */
class WebhookSubscriptionTest {

    private static WebhookSubscription subscribedTo(String eventType) {
        return WebhookSubscription.of(UUID.randomUUID(), eventType);
    }

    @Test
    void anExactSubscriptionMatchesOnlyItsOwnEventType() {
        WebhookSubscription subscription = subscribedTo("payment.authorized");

        assertThat(subscription.matches("payment.authorized")).isTrue();
        assertThat(subscription.matches("payment.captured")).isFalse();
    }

    @Test
    void theWildcardSubscriptionMatchesEveryEventType() {
        WebhookSubscription subscription = subscribedTo(WebhookSubscription.ALL_EVENT_TYPES);

        assertThat(subscription.matches("payment.authorized")).isTrue();
        assertThat(subscription.matches("payment.refunded")).isTrue();
        // Including one that does not exist yet — clients and endpoints must tolerate
        // new event types without a change (§4.10: additive changes are never breaking).
        assertThat(subscription.matches("some.future.event")).isTrue();
    }

    @Test
    void matchingIsExactRatherThanPrefixBased() {
        WebhookSubscription subscription = subscribedTo("payment.authorized");

        // A prefix-matching implementation would leak related-but-unsubscribed events;
        // "payment.*"-style patterns are deliberately not supported, only "*".
        assertThat(subscription.matches("payment")).isFalse();
        assertThat(subscription.matches("payment.authorized.retried")).isFalse();
        assertThat(subscription.matches("PAYMENT.AUTHORIZED")).isFalse();
    }
}
