package com.paymentflow.notification.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The endpoint aggregate's own rules, with no Spring and no database — the two that
 * carry real consequences are the dual-secret grace window (an endpoint that rolls its
 * secret must not drop deliveries) and the consecutive-failure streak (auto-disable
 * counts *consecutive* failures, so any success must reset it, or a merely flaky
 * endpoint would eventually be disabled as if it were dead).
 */
class WebhookEndpointTest {

    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");

    private static WebhookEndpoint anEndpoint() {
        return WebhookEndpoint.register(UUID.randomUUID(), "test", "https://merchant.test/hooks",
                "Primary endpoint", "2026-08-01", "enc-a", "whsec_aaaa", "ops@merchant.test");
    }

    @Test
    void aNewlyRegisteredEndpointIsEnabledWithNoFailureStreakAndNoPreviousSecret() {
        WebhookEndpoint endpoint = anEndpoint();

        assertThat(endpoint.isEnabled()).isTrue();
        assertThat(endpoint.getConsecutiveFailureCount()).isZero();
        assertThat(endpoint.getDisabledAt()).isNull();
        assertThat(endpoint.getDisabledReason()).isNull();
        assertThat(endpoint.isMigratedFromLegacy()).isFalse();
        assertThat(endpoint.hasUsablePreviousSecret(NOW)).isFalse();
    }

    @Test
    void anAdoptedLegacyEndpointIsFlaggedSoTheDeprecationStaysAuditable() {
        WebhookEndpoint endpoint = WebhookEndpoint.adoptLegacy(UUID.randomUUID(), "live",
                "https://merchant.test/legacy", "2026-08-01", "enc-a", "whsec_aaaa", "ops@merchant.test");

        assertThat(endpoint.isMigratedFromLegacy()).isTrue();
        assertThat(endpoint.isEnabled()).isTrue();
    }

    @Test
    void rotatingKeepsTheSupersededSecretUsableUntilTheGraceWindowElapses() {
        WebhookEndpoint endpoint = anEndpoint();

        endpoint.rotateSecret("enc-b", "whsec_bbbb", Duration.ofHours(24), NOW);

        assertThat(endpoint.getSigningSecretEncrypted()).isEqualTo("enc-b");
        assertThat(endpoint.getSigningSecretPrefix()).isEqualTo("whsec_bbbb");
        assertThat(endpoint.getPreviousSecretEncrypted()).isEqualTo("enc-a");
        // Usable one second before expiry, unusable at it — a pure time comparison, so no
        // scheduled job is required and none can drift out of sync (D120's shape).
        assertThat(endpoint.hasUsablePreviousSecret(NOW.plus(Duration.ofHours(24)).minusSeconds(1))).isTrue();
        assertThat(endpoint.hasUsablePreviousSecret(NOW.plus(Duration.ofHours(24)))).isFalse();
    }

    @Test
    void aSuccessfulDeliveryResetsTheConsecutiveFailureStreak() {
        WebhookEndpoint endpoint = anEndpoint();

        endpoint.recordDeliveryFailure();
        endpoint.recordDeliveryFailure();
        endpoint.recordDeliveryFailure();
        assertThat(endpoint.getConsecutiveFailureCount()).isEqualTo(3);

        endpoint.recordDeliverySuccess();

        assertThat(endpoint.getConsecutiveFailureCount()).isZero();
    }

    @Test
    void aPlatformAutoDisableRecordsItsReasonWhileAMerchantDisableDoesNot() {
        WebhookEndpoint merchantDisabled = anEndpoint();
        merchantDisabled.disable();

        assertThat(merchantDisabled.isEnabled()).isFalse();
        assertThat(merchantDisabled.getDisabledAt()).isNull();
        assertThat(merchantDisabled.getDisabledReason()).isNull();

        WebhookEndpoint autoDisabled = anEndpoint();
        autoDisabled.autoDisable(EndpointDisableReason.CONSECUTIVE_FAILURES, NOW);

        assertThat(autoDisabled.isEnabled()).isFalse();
        assertThat(autoDisabled.getDisabledAt()).isEqualTo(NOW);
        assertThat(autoDisabled.getDisabledReason()).isEqualTo(EndpointDisableReason.CONSECUTIVE_FAILURES);
    }

    @Test
    void reEnablingClearsThePlatformsDisableAnnotationsAndTheFailureStreak() {
        WebhookEndpoint endpoint = anEndpoint();
        endpoint.recordDeliveryFailure();
        endpoint.autoDisable(EndpointDisableReason.CONSECUTIVE_FAILURES, NOW);

        endpoint.enable();

        assertThat(endpoint.isEnabled()).isTrue();
        assertThat(endpoint.getDisabledAt()).isNull();
        assertThat(endpoint.getDisabledReason()).isNull();
        // Without this reset a re-enabled endpoint would be one failure away from being
        // auto-disabled again, which is not what "re-enable" means to a merchant.
        assertThat(endpoint.getConsecutiveFailureCount()).isZero();
    }
}
