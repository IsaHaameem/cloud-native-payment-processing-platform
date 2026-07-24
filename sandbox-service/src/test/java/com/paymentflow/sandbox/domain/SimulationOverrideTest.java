package com.paymentflow.sandbox.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code isActive} in isolation, no Spring context or persistence needed — the three
 * independent ways an override ends (revoked, expired, exhausted by count), each
 * tested without the other two interfering.
 */
class SimulationOverrideTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void unboundedByCountOrTimeIsAlwaysActive() {
        SimulationOverride override = create(null, null);

        assertThat(override.isActive(NOW)).isTrue();
    }

    @Test
    void countBoundedIsActiveWhileRemainingCountIsPositive() {
        SimulationOverride override = create(3, null);

        assertThat(override.isActive(NOW)).isTrue();
    }

    @Test
    void exhaustedByCountIsNotActive() {
        SimulationOverride override = create(0, null);

        assertThat(override.isActive(NOW)).isFalse();
    }

    @Test
    void timeBoundedIsActiveBeforeItsExpiry() {
        SimulationOverride override = create(null, NOW.plusSeconds(60));

        assertThat(override.isActive(NOW)).isTrue();
    }

    @Test
    void pastItsExpiryIsNotActive() {
        SimulationOverride override = create(null, NOW.minusSeconds(1));

        assertThat(override.isActive(NOW)).isFalse();
    }

    @Test
    void explicitlyRevokedIsNeverActiveEvenWithRemainingCountAndTimeLeft() throws Exception {
        SimulationOverride override = create(5, NOW.plusSeconds(60));
        setRevokedAt(override, NOW.minusSeconds(1));

        assertThat(override.isActive(NOW)).isFalse();
    }

    private static SimulationOverride create(Integer remainingCount, Instant expiresAt) {
        return SimulationOverride.create(UUID.randomUUID(), "test", SimulationScenario.FORCE_DECLINE,
                "card_declined", null, null, remainingCount, expiresAt);
    }

    /** No setter exists on the entity by design (see its own javadoc) — reflection is the only way a test can simulate a revoke without going through the repository's {@code @Modifying} query. */
    private static void setRevokedAt(SimulationOverride override, Instant revokedAt) throws Exception {
        var field = SimulationOverride.class.getDeclaredField("revokedAt");
        field.setAccessible(true);
        field.set(override, revokedAt);
    }
}
