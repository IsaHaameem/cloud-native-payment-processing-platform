package com.paymentflow.audit;

import com.paymentflow.audit.domain.AuditLogEntry;
import com.paymentflow.audit.dto.EventResponse;
import com.paymentflow.audit.repository.AuditLogEntryRepository;
import com.paymentflow.audit.service.EventQueryService;
import com.paymentflow.common.dto.event.CanonicalEventType;
import com.paymentflow.common.dto.page.CursorPage;
import com.paymentflow.common.exception.BadRequestException;
import com.paymentflow.common.exception.ResourceNotFoundException;
import com.paymentflow.common.query.CursorCodec;
import com.paymentflow.common.query.ListQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Events API (M19.5) against real Postgres.
 *
 * <p>The two assertions that carry the most weight are the isolation sweep (M19's own
 * criterion — every new endpoint refuses cross-merchant and cross-mode access with 404)
 * and the one proving an internal-only event never surfaces: audit-service records
 * {@code merchant.events} alongside payment events, and a merchant's event feed leaking
 * key-issuance records would be a real disclosure, not a cosmetic bug.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
class EventsApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private AuditLogEntryRepository repository;
    @Autowired
    private EventQueryService eventQueryService;
    @Autowired
    private CursorCodec cursorCodec;

    private ListQuery query(UUID merchantId, String mode, int limit) {
        return ListQuery.resolve(limit, null, null, null, cursorCodec, merchantId, mode);
    }

    private AuditLogEntry record(UUID merchantId, String mode, String internalType, Instant occurredAt) {
        return repository.saveAndFlush(AuditLogEntry.of(UUID.randomUUID(), internalType,
                UUID.randomUUID().toString(), occurredAt, "corr", mode, merchantId,
                "{\"merchantId\":\"" + merchantId + "\",\"amountMinor\":5000}"));
    }

    @Test
    void anEventIsReturnedInTheCanonicalShapeWithADerivedPublicId() {
        UUID merchantId = UUID.randomUUID();
        AuditLogEntry entry = record(merchantId, "test", "PaymentAuthorized", Instant.now());

        EventResponse event = eventQueryService.get(merchantId, "test",
                CanonicalEventType.eventRefFor(entry.getEventId()));

        // The id is derived from the envelope id, so it is byte-identical to the one
        // notification-service put in the webhook body — without either service knowing
        // the other exists.
        assertThat(event.id()).isEqualTo("evt_" + entry.getEventId().toString().replace("-", ""));
        assertThat(event.object()).isEqualTo("event");
        assertThat(event.type()).isEqualTo("payment.authorized");
        assertThat(event.mode()).isEqualTo("test");
        assertThat(event.data().get("amountMinor").asLong()).isEqualTo(5000);
    }

    @Test
    void everyReadIsScopedToTheCallersOwnMerchantAndMode() {
        UUID merchantId = UUID.randomUUID();
        UUID otherMerchant = UUID.randomUUID();
        AuditLogEntry entry = record(merchantId, "test", "PaymentCaptured", Instant.now());
        String ref = CanonicalEventType.eventRefFor(entry.getEventId());

        // A real id, refused for another merchant and for the other mode — 404, never 403,
        // which would confirm the event exists (D102).
        assertThatThrownBy(() -> eventQueryService.get(otherMerchant, "test", ref))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> eventQueryService.get(merchantId, "live", ref))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(eventQueryService.list(merchantId, "test", query(merchantId, "test", 50), null).data()).hasSize(1);
        assertThat(eventQueryService.list(otherMerchant, "test", query(otherMerchant, "test", 50), null).data())
                .isEmpty();
        assertThat(eventQueryService.list(merchantId, "live", query(merchantId, "live", 50), null).data()).isEmpty();
    }

    @Test
    void anInternalOnlyEventNeverAppearsInAMerchantsFeed() {
        UUID merchantId = UUID.randomUUID();
        record(merchantId, "test", "PaymentAuthorized", Instant.now());
        // audit records merchant.events too — an operator's trail, not a merchant's feed.
        AuditLogEntry keyEvent = record(merchantId, "test", "ApiKeyIssued", Instant.now());

        CursorPage<EventResponse> page =
                eventQueryService.list(merchantId, "test", query(merchantId, "test", 50), null);

        assertThat(page.data()).hasSize(1);
        assertThat(page.data().getFirst().type()).isEqualTo("payment.authorized");
        // Not merely absent from the list — unreachable by direct id too, so knowing the
        // id buys nothing.
        assertThatThrownBy(() -> eventQueryService.get(merchantId, "test",
                CanonicalEventType.eventRefFor(keyEvent.getEventId())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theFeedIsOrderedByWhenThingsHappenedNotWhenTheyWereRecorded() {
        UUID merchantId = UUID.randomUUID();
        Instant earlier = Instant.now().minusSeconds(600);
        Instant later = Instant.now();
        // Written in the opposite order to how they occurred — which is exactly what a
        // Kafka redelivery produces.
        record(merchantId, "test", "PaymentCaptured", later);
        record(merchantId, "test", "PaymentCreated", earlier);

        CursorPage<EventResponse> page =
                eventQueryService.list(merchantId, "test", query(merchantId, "test", 50), null);

        assertThat(page.data()).extracting(EventResponse::type)
                .containsExactly("payment.captured", "payment.created");
    }

    @Test
    void theTypeFilterAcceptsCanonicalNamesAndRejectsUnknownOnes() {
        UUID merchantId = UUID.randomUUID();
        record(merchantId, "test", "PaymentAuthorized", Instant.now());
        record(merchantId, "test", "PaymentCaptured", Instant.now());

        assertThat(eventQueryService.list(merchantId, "test", query(merchantId, "test", 50), "payment.captured")
                .data()).hasSize(1);
        // A typo returns 400 naming the vocabulary, not an empty page the merchant then
        // has to explain to themselves.
        assertThatThrownBy(() -> eventQueryService.list(merchantId, "test",
                query(merchantId, "test", 50), "payment.captureed"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("payment.captured");
    }

    @Test
    void aMalformedEventIdIsRejectedAsABadRequestRatherThanCrashing() {
        UUID merchantId = UUID.randomUUID();

        for (String bad : new String[]{"", "evt_", "not-an-id", "evt_zzzz"}) {
            assertThatThrownBy(() -> eventQueryService.get(merchantId, "test", bad))
                    .describedAs("event id '%s'", bad)
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Test
    void paginationReturnsEveryEventExactlyOnce() {
        UUID merchantId = UUID.randomUUID();
        Instant base = Instant.now().minusSeconds(3600);
        for (int i = 0; i < 7; i++) {
            record(merchantId, "test", "PaymentCreated", base.plusSeconds(i));
        }

        java.util.List<String> collected = new java.util.ArrayList<>();
        String cursor = null;
        for (int p = 0; p < 5; p++) {
            ListQuery paged = ListQuery.resolve(3, cursor, null, null, cursorCodec, merchantId, "test");
            CursorPage<EventResponse> page = eventQueryService.list(merchantId, "test", paged, null);
            page.data().forEach(event -> collected.add(event.id()));
            if (!page.hasMore()) {
                break;
            }
            cursor = page.nextCursor();
        }

        assertThat(collected).hasSize(7).doesNotHaveDuplicates();
    }
}
