package com.paymentflow.notification.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The canonical event's two construction rules. Both are contracts other milestones
 * depend on rather than incidental details: M19's Events API re-derives {@code evt_}
 * ids from audit-service's own copy of the envelope id, and D125's null-mode-means-live
 * reading is what keeps a pre-M16 or mode-less event queryable at all.
 */
class WebhookEventTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-01T12:00:00Z");

    private static WebhookEvent eventWithMode(UUID sourceEventId, String declaredMode) {
        return WebhookEvent.of(sourceEventId, UUID.randomUUID(), declaredMode, "payment.authorized",
                "2026-08-01", "{}", OCCURRED_AT, "corr-1");
    }

    @Test
    void thePublicIdIsDerivedDeterministicallyFromTheSourceEventId() {
        UUID sourceEventId = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301");

        assertThat(WebhookEvent.eventRefFor(sourceEventId)).isEqualTo("evt_3f2504e04f8941d39a0c0305e82c3301");
        assertThat(eventWithMode(sourceEventId, "test").getEventRef())
                .isEqualTo(WebhookEvent.eventRefFor(sourceEventId));
    }

    @Test
    void thePublicIdIsStableAcrossRepeatedDerivations() {
        UUID sourceEventId = UUID.randomUUID();

        // The property M19 relies on: a second service holding only the envelope id
        // reproduces the identical public id with no lookup and no coordination.
        assertThat(WebhookEvent.eventRefFor(sourceEventId)).isEqualTo(WebhookEvent.eventRefFor(sourceEventId));
        assertThat(WebhookEvent.eventRefFor(sourceEventId)).startsWith(WebhookEvent.ID_PREFIX);
    }

    @Test
    void aDeclaredModeIsRecordedVerbatim() {
        assertThat(eventWithMode(UUID.randomUUID(), "test").getMode()).isEqualTo("test");
        assertThat(eventWithMode(UUID.randomUUID(), "live").getMode()).isEqualTo("live");
    }

    @Test
    void anAbsentEnvelopeModeIsReadAsLive() {
        // D125: a consumer reading a null mode treats it as live. Unlike email_log and
        // webhook_deliveries (D126's recorder semantics), this table is queried *by*
        // mode, so a null here would be unqueryable rather than merely unknown.
        assertThat(eventWithMode(UUID.randomUUID(), null).getMode()).isEqualTo("live");
        assertThat(eventWithMode(UUID.randomUUID(), "  ").getMode()).isEqualTo("live");
    }
}
