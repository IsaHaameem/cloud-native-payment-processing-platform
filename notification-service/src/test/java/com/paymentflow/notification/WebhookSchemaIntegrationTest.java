package com.paymentflow.notification;

import com.paymentflow.notification.domain.AttemptOutcome;
import com.paymentflow.notification.domain.WebhookDelivery;
import com.paymentflow.notification.domain.WebhookDeliveryAttempt;
import com.paymentflow.notification.domain.WebhookEndpoint;
import com.paymentflow.notification.domain.WebhookEvent;
import com.paymentflow.notification.domain.WebhookSubscription;
import com.paymentflow.notification.repository.WebhookDeliveryAttemptRepository;
import com.paymentflow.notification.repository.WebhookDeliveryRepository;
import com.paymentflow.notification.repository.WebhookEndpointRepository;
import com.paymentflow.notification.repository.WebhookEventRepository;
import com.paymentflow.notification.repository.WebhookSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M18.1's completion proof against real Postgres: {@code V4__webhooks.sql} applies on
 * top of the existing three migrations, the four new entities validate against it
 * (Hibernate runs with {@code ddl-auto: validate}, so the context simply failing to
 * start would be the mapping test), the mode/merchant scoping the repositories promise
 * actually isolates, and the schema's own CHECK/UNIQUE constraints reject rows the
 * application layer might one day forget to.
 *
 * <p>Postgres only, with the Kafka listeners disabled: this sub-milestone adds no
 * messaging at all, and standing up a broker to test a schema would buy nothing but
 * runtime. {@code NotificationIntegrationTest} remains the test that exercises the real
 * Kafka pipeline, and it is deliberately left untouched by M18.1 — V1's delivery path
 * is unchanged here.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
class WebhookSchemaIntegrationTest {

    private static final String API_VERSION = "2026-08-01";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private WebhookEndpointRepository endpointRepository;
    @Autowired
    private WebhookSubscriptionRepository subscriptionRepository;
    @Autowired
    private WebhookEventRepository eventRepository;
    @Autowired
    private WebhookDeliveryRepository deliveryRepository;
    @Autowired
    private WebhookDeliveryAttemptRepository attemptRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private WebhookEndpoint saveEndpoint(UUID merchantId, String mode, String url) {
        return endpointRepository.save(WebhookEndpoint.register(merchantId, mode, url, "An endpoint",
                API_VERSION, "enc-" + UUID.randomUUID(), "whsec_test", "ops@merchant.test"));
    }

    @Test
    void endpointsAreVisibleOnlyWithinTheirOwnMerchantAndMode() {
        UUID merchantA = UUID.randomUUID();
        UUID merchantB = UUID.randomUUID();
        WebhookEndpoint testEndpoint = saveEndpoint(merchantA, "test", "https://a.test/test-hook");
        saveEndpoint(merchantA, "live", "https://a.test/live-hook");
        saveEndpoint(merchantB, "test", "https://b.test/hook");

        assertThat(endpointRepository.findByMerchantIdAndModeOrderByCreatedAtAsc(merchantA, "test"))
                .extracting(WebhookEndpoint::getUrl)
                .containsExactly("https://a.test/test-hook");

        // The D102 guarantee: a real id from the other mode, and a real id from another
        // merchant, both resolve to empty — which callers surface as 404, never 403.
        assertThat(endpointRepository.findByIdAndMerchantIdAndMode(testEndpoint.getId(), merchantA, "test"))
                .isPresent();
        assertThat(endpointRepository.findByIdAndMerchantIdAndMode(testEndpoint.getId(), merchantA, "live"))
                .isEmpty();
        assertThat(endpointRepository.findByIdAndMerchantIdAndMode(testEndpoint.getId(), merchantB, "test"))
                .isEmpty();
    }

    @Test
    void theSameUrlMayBeRegisteredInBothModesButNotTwiceWithinOne() {
        UUID merchantId = UUID.randomUUID();
        String url = "https://shared.test/hook";

        saveEndpoint(merchantId, "test", url);
        saveEndpoint(merchantId, "live", url);

        // A second registration of the same URL in the same mode would silently double
        // every delivery to it — a duplicate-webhook bug the merchant would diagnose as
        // a platform fault.
        assertThatThrownBy(() -> saveEndpoint(merchantId, "test", url))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyEnabledEndpointsAreCandidatesForFanOut() {
        UUID merchantId = UUID.randomUUID();
        saveEndpoint(merchantId, "test", "https://a.test/enabled");
        WebhookEndpoint disabled = saveEndpoint(merchantId, "test", "https://a.test/disabled");
        disabled.disable();
        endpointRepository.save(disabled);

        assertThat(endpointRepository.findByMerchantIdAndModeAndEnabledTrue(merchantId, "test"))
                .extracting(WebhookEndpoint::getUrl)
                .containsExactly("https://a.test/enabled");
    }

    @Test
    void deletingAnEndpointRemovesItsSubscriptionsWithIt() {
        WebhookEndpoint endpoint = saveEndpoint(UUID.randomUUID(), "test", "https://a.test/cascade");
        subscriptionRepository.save(WebhookSubscription.of(endpoint.getId(), "payment.authorized"));
        subscriptionRepository.save(WebhookSubscription.of(endpoint.getId(), "payment.captured"));
        assertThat(subscriptionRepository.findByEndpointId(endpoint.getId())).hasSize(2);

        endpointRepository.delete(endpoint);

        // Enforced by the FK's ON DELETE CASCADE, not by application code remembering to
        // clean up — an orphaned subscription would be an endpoint-less delivery target.
        assertThat(subscriptionRepository.findByEndpointId(endpoint.getId())).isEmpty();
    }

    @Test
    void theSameEventTypeCannotBeSubscribedTwiceOnOneEndpoint() {
        WebhookEndpoint endpoint = saveEndpoint(UUID.randomUUID(), "test", "https://a.test/dupe-sub");
        subscriptionRepository.save(WebhookSubscription.of(endpoint.getId(), "payment.authorized"));

        assertThatThrownBy(() -> subscriptionRepository
                .save(WebhookSubscription.of(endpoint.getId(), "payment.authorized")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void oneInternalEventProducesAtMostOneCanonicalEvent() {
        UUID sourceEventId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        WebhookEvent event = eventRepository.save(WebhookEvent.of(sourceEventId, merchantId, "test",
                "payment.authorized", API_VERSION, "{\"object\":\"payment\"}", Instant.now(), "corr-1"));

        assertThat(eventRepository.findBySourceEventId(sourceEventId)).isPresent();
        assertThat(eventRepository.findByEventRefAndMerchantIdAndMode(event.getEventRef(), merchantId, "test"))
                .isPresent();
        // Same public id, wrong mode — the read is scoped, so it does not resolve.
        assertThat(eventRepository.findByEventRefAndMerchantIdAndMode(event.getEventRef(), merchantId, "live"))
                .isEmpty();

        // The dedup gate for at-least-once redelivery (D2), enforced by the database.
        assertThatThrownBy(() -> eventRepository.save(WebhookEvent.of(sourceEventId, merchantId, "test",
                "payment.authorized", API_VERSION, "{}", Instant.now(), "corr-2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptsAreRecordedAgainstADeliveryInOrderWithTheirRequestAndResponseIntact() {
        WebhookDelivery delivery = deliveryRepository.save(WebhookDelivery.pending(UUID.randomUUID(),
                UUID.randomUUID(), "test", "https://a.test/attempts", "{}"));

        attemptRepository.save(WebhookDeliveryAttempt.transportFailed(delivery.getId(), 1,
                "https://a.test/attempts", "{\"PaymentFlow-Signature\":\"t=1,v1=aa\"}", "{\"id\":\"evt_1\"}",
                3000, "connect timed out"));
        attemptRepository.save(WebhookDeliveryAttempt.answered(delivery.getId(), 2, 200,
                "https://a.test/attempts", "{\"PaymentFlow-Signature\":\"t=2,v1=bb\"}", "{\"id\":\"evt_1\"}",
                "{\"Content-Type\":\"text/plain\"}", "ok", 42));

        List<WebhookDeliveryAttempt> attempts =
                attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(delivery.getId());

        assertThat(attempts).extracting(WebhookDeliveryAttempt::getOutcome)
                .containsExactly(AttemptOutcome.FAILED_TRANSPORT, AttemptOutcome.SUCCEEDED);
        assertThat(attempts.getFirst().getResponseStatus()).isNull();
        assertThat(attempts.getFirst().getError()).isEqualTo("connect timed out");
        // The request is stored verbatim per attempt: the retry re-signed with a fresh
        // timestamp, so the two attempts genuinely sent different bytes.
        assertThat(attempts.getFirst().getRequestHeaders()).contains("t=1");
        assertThat(attempts.getLast().getRequestHeaders()).contains("t=2");
        assertThat(attempts.getLast().getResponseBody()).isEqualTo("ok");
        assertThat(attemptRepository.countByDeliveryId(delivery.getId())).isEqualTo(2);
    }

    @Test
    void aBlockedAttemptIsRecordedRatherThanSilentlySkipped() {
        WebhookDelivery delivery = deliveryRepository.save(WebhookDelivery.pending(UUID.randomUUID(),
                UUID.randomUUID(), "test", "http://169.254.169.254/latest/meta-data", "{}"));

        attemptRepository.save(WebhookDeliveryAttempt.blocked(delivery.getId(), 1,
                "http://169.254.169.254/latest/meta-data", "{}", "{}", "Destination resolves to a blocked range."));

        WebhookDeliveryAttempt attempt =
                attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(delivery.getId()).getFirst();

        // A merchant whose endpoint was never contacted must be told that, not shown a
        // connection error implying we tried.
        assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.BLOCKED);
        assertThat(attempt.getResponseStatus()).isNull();
        assertThat(attempt.getDurationMs()).isNull();
    }

    @Test
    void theDatabaseRejectsIncoherentRowsTheApplicationLayerMightOneDayLetThrough() {
        UUID merchantId = UUID.randomUUID();

        // mode must be test or live — nothing else, ever.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into notification.webhook_endpoints
                    (merchant_id, mode, url, api_version, signing_secret_encrypted, signing_secret_prefix)
                values (?, 'sandbox', 'https://a.test/bad-mode', ?, 'h', 'whsec_x')""", merchantId, API_VERSION))
                .isInstanceOf(DataIntegrityViolationException.class);

        // An auto-disabled endpoint that is somehow still enabled is a contradiction.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into notification.webhook_endpoints
                    (merchant_id, mode, url, api_version, signing_secret_encrypted, signing_secret_prefix,
                     enabled, disabled_at, disabled_reason)
                values (?, 'test', 'https://a.test/bad-disable', ?, 'h', 'whsec_x',
                        true, now(), 'CONSECUTIVE_FAILURES')""", merchantId, API_VERSION))
                .isInstanceOf(DataIntegrityViolationException.class);

        // A rotation window with a previous secret but no expiry would never lapse.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into notification.webhook_endpoints
                    (merchant_id, mode, url, api_version, signing_secret_encrypted, signing_secret_prefix,
                     previous_secret_encrypted)
                values (?, 'test', 'https://a.test/bad-rotation', ?, 'h', 'whsec_x', 'old')""",
                merchantId, API_VERSION))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theDatabaseRejectsAnAttemptWhoseOutcomeAndStatusDisagree() {
        WebhookDelivery delivery = deliveryRepository.save(WebhookDelivery.pending(UUID.randomUUID(),
                UUID.randomUUID(), "test", "https://a.test/shape", "{}"));

        // SUCCEEDED without a status, and FAILED_TRANSPORT with one, are both incoherent:
        // the factories on WebhookDeliveryAttempt make them unreachable, and this proves
        // the database is the backstop rather than the only guard being in Java.
        assertThatThrownBy(() -> insertRawAttempt(delivery.getId(), 1, "SUCCEEDED", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRawAttempt(delivery.getId(), 2, "FAILED_TRANSPORT", 200))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertRawAttempt(UUID deliveryId, int attemptNumber, String outcome, Integer responseStatus) {
        jdbcTemplate.update("""
                insert into notification.webhook_delivery_attempts
                    (delivery_id, attempt_number, outcome, request_url, request_headers, request_body,
                     response_status)
                values (?, ?, ?, 'https://a.test/shape', '{}'::jsonb, '{}', ?)""",
                deliveryId, attemptNumber, outcome, responseStatus);
    }
}
