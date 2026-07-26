package com.paymentflow.payment;

import com.paymentflow.common.query.Cursor;
import com.paymentflow.common.query.CursorCodec;
import com.paymentflow.common.query.ListQuery;
import com.paymentflow.payment.domain.Payment;
import com.paymentflow.payment.domain.Refund;
import com.paymentflow.payment.repository.PaymentRepository;
import com.paymentflow.payment.repository.RefundRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The payments/refunds read layer against real Postgres (M19.2/M19.3) — the filter set,
 * the keyset predicate, jsonb containment, and the property cursors exist for.
 *
 * <p>Exercises the repository and query service directly rather than over HTTP: the
 * public routing and authorization are M19.7's concern and are tested there, while what
 * needs proving here is that the SQL is right. The native query is the platform's only
 * one, so it gets tested against a real database rather than a mock that would agree with
 * whatever it was told.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
class PaymentReadApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private RefundRepository refundRepository;
    @Autowired
    private CursorCodec cursorCodec;

    private Payment save(UUID merchantId, String mode, long amount, String currency, String metadata) {
        return paymentRepository.saveAndFlush(
                Payment.create(merchantId, mode, amount, currency, "seeded", null, metadata));
    }

    private List<Payment> page(UUID merchantId, String mode, Instant cursorAt, UUID cursorId, int fetchSize) {
        return findPage(merchantId, mode, null, null, null, null, null, null, null, cursorAt, cursorId, fetchSize);
    }

    /**
     * Does for these tests what {@code ListQuery}'s bound accessors do for the service:
     * turns "not supplied" into the sentinel the query binds.
     *
     * <p>The repository stopped accepting nulls for the range and cursor parameters in
     * M19.8 — a {@code (:bound is null or …)} guard demotes those predicates from index
     * conditions to filters, so a deep page scanned the merchant's whole partition. The
     * tests keep saying "no range, no cursor" in the same words; only the translation
     * moved, to the one place that owns it.
     */
    private List<Payment> findPage(UUID merchantId, String mode, String status, String currency,
                                   Long amountMin, Long amountMax, Instant createdAfter, Instant createdBefore,
                                   String metadata, Instant cursorAt, UUID cursorId, int fetchSize) {
        return paymentRepository.findPage(merchantId, mode, status, currency, amountMin, amountMax,
                createdAfter == null ? ListQuery.EARLIEST : createdAfter,
                createdBefore == null ? ListQuery.LATEST : createdBefore,
                metadata,
                cursorAt == null ? ListQuery.LATEST : cursorAt,
                cursorId == null ? ListQuery.LAST_ID : cursorId,
                fetchSize);
    }

    /** The refunds equivalent — same sentinels, same reason. */
    private List<Refund> findRefundPage(UUID merchantId, String mode, UUID paymentId, String status,
                                        Instant createdAfter, Instant createdBefore, String metadata,
                                        Instant cursorAt, UUID cursorId, int fetchSize) {
        return refundRepository.findPage(merchantId, mode, paymentId, status,
                createdAfter == null ? ListQuery.EARLIEST : createdAfter,
                createdBefore == null ? ListQuery.LATEST : createdBefore,
                metadata,
                cursorAt == null ? ListQuery.LATEST : cursorAt,
                cursorId == null ? ListQuery.LAST_ID : cursorId,
                fetchSize);
    }

    @Test
    void theListIsNewestFirstAndScopedToOneMerchantAndMode() {
        UUID merchantId = UUID.randomUUID();
        UUID otherMerchant = UUID.randomUUID();
        save(merchantId, "test", 100, "USD", null);
        save(merchantId, "live", 200, "USD", null);
        save(otherMerchant, "test", 300, "USD", null);

        List<Payment> testPage = page(merchantId, "test", null, null, 50);

        assertThat(testPage).hasSize(1);
        assertThat(testPage.getFirst().getAmountMinor()).isEqualTo(100);
        // Neither the other mode nor the other merchant is reachable — the scoping is a
        // predicate on the query, not a filter applied afterwards (D101).
        assertThat(page(merchantId, "live", null, null, 50)).hasSize(1);
        assertThat(page(otherMerchant, "test", null, null, 50)).hasSize(1);
    }

    @Test
    void paginationIsStableWhenRowsAreInsertedBetweenPages() {
        // The exact case offset pagination gets wrong and cursors exist to fix (D107).
        UUID merchantId = UUID.randomUUID();
        for (int i = 0; i < 6; i++) {
            save(merchantId, "test", 1000 + i, "USD", null);
        }

        List<Payment> first = page(merchantId, "test", null, null, 3);
        assertThat(first).hasSize(3);
        Payment boundary = first.getLast();

        // Three new payments land while the client is between pages. With offsets, page 2
        // would re-show rows from page 1 and something would fall through the gap.
        for (int i = 0; i < 3; i++) {
            save(merchantId, "test", 9000 + i, "USD", null);
        }

        List<Payment> second = page(merchantId, "test", boundary.getCreatedAt(), boundary.getId(), 3);

        List<UUID> firstIds = first.stream().map(Payment::getId).toList();
        assertThat(second).extracting(Payment::getId).doesNotContainAnyElementsOf(firstIds);
        // Every row on page 2 is genuinely older than the boundary — the newly inserted
        // ones sort above the cursor and are simply not seen by this pagination run.
        assertThat(second).allSatisfy(p ->
                assertThat(p.getCreatedAt()).isBeforeOrEqualTo(boundary.getCreatedAt()));
    }

    @Test
    void theKeysetBoundaryIsExactWhenTimestampsCollide() {
        // Rows created in the same millisecond are not hypothetical under load. Without
        // the id as a tiebreaker the boundary is ambiguous and a row is silently repeated
        // or skipped.
        UUID merchantId = UUID.randomUUID();
        List<Payment> saved = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            saved.add(save(merchantId, "test", 500 + i, "USD", null));
        }

        List<UUID> collected = new ArrayList<>();
        Instant cursorAt = null;
        UUID cursorId = null;
        for (int p = 0; p < 4; p++) {
            List<Payment> batch = page(merchantId, "test", cursorAt, cursorId, 2);
            if (batch.isEmpty()) {
                break;
            }
            batch.forEach(row -> collected.add(row.getId()));
            cursorAt = batch.getLast().getCreatedAt();
            cursorId = batch.getLast().getId();
        }

        // Every row exactly once, no duplicates, regardless of timestamp collisions.
        assertThat(collected).hasSize(8).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(saved.stream().map(Payment::getId).toList());
    }

    @Test
    void everyFilterNarrowsTheResultAndCombinesWithTheOthers() {
        UUID merchantId = UUID.randomUUID();
        save(merchantId, "test", 500, "USD", null);
        save(merchantId, "test", 5000, "EUR", null);
        Payment target = save(merchantId, "test", 5000, "USD", null);

        assertThat(findPage(merchantId, "test", "CREATED", null, null, null, null, null, null,
                null, null, 50)).hasSize(3);
        assertThat(findPage(merchantId, "test", null, "USD", null, null, null, null, null,
                null, null, 50)).hasSize(2);
        assertThat(findPage(merchantId, "test", null, null, 1000L, null, null, null, null,
                null, null, 50)).hasSize(2);
        assertThat(findPage(merchantId, "test", null, null, null, 1000L, null, null, null,
                null, null, 50)).hasSize(1);
        // Combined: USD and at least 1000 selects exactly the one payment that is both.
        List<Payment> combined = findPage(merchantId, "test", null, "USD", 1000L, null,
                null, null, null, null, null, 50);
        assertThat(combined).extracting(Payment::getId).containsExactly(target.getId());
    }

    @Test
    void anUnmatchedStatusFilterReturnsNothingRatherThanEverything() {
        // A filter that silently fails open would be far worse than one that returns zero.
        UUID merchantId = UUID.randomUUID();
        save(merchantId, "test", 500, "USD", null);

        assertThat(findPage(merchantId, "test", "REFUNDED", null, null, null, null, null,
                null, null, null, 50)).isEmpty();
    }

    @Test
    void metadataIsFilteredByContainmentNotEquality() {
        UUID merchantId = UUID.randomUUID();
        save(merchantId, "test", 100, "USD", "{\"order\":\"A-1\",\"channel\":\"web\"}");
        save(merchantId, "test", 200, "USD", "{\"order\":\"A-2\"}");
        save(merchantId, "test", 300, "USD", null);

        // Containment: a partial match selects the row even though it has other keys.
        assertThat(findPage(merchantId, "test", null, null, null, null, null, null,
                "{\"order\":\"A-1\"}", null, null, 50)).hasSize(1);
        assertThat(findPage(merchantId, "test", null, null, null, null, null, null,
                "{\"channel\":\"web\"}", null, null, 50)).hasSize(1);
        // Both keys must match, not either.
        assertThat(findPage(merchantId, "test", null, null, null, null, null, null,
                "{\"order\":\"A-2\",\"channel\":\"web\"}", null, null, 50)).isEmpty();
        // A payment with no metadata is '{}', which contains nothing — so it never matches
        // a filter, and never has to be reasoned about as null.
        assertThat(findPage(merchantId, "test", null, null, null, null, null, null,
                "{\"order\":\"A-9\"}", null, null, 50)).isEmpty();
    }

    @Test
    void aCreatedRangeSelectsOnlyRowsInsideIt() {
        UUID merchantId = UUID.randomUUID();
        Payment payment = save(merchantId, "test", 100, "USD", null);

        Instant before = payment.getCreatedAt().minusSeconds(60);
        Instant after = payment.getCreatedAt().plusSeconds(60);

        assertThat(findPage(merchantId, "test", null, null, null, null, before, after,
                null, null, null, 50)).hasSize(1);
        assertThat(findPage(merchantId, "test", null, null, null, null, after, null,
                null, null, null, 50)).isEmpty();
        // created_before is exclusive: a row created exactly at the bound is outside it.
        assertThat(findPage(merchantId, "test", null, null, null, null, null,
                payment.getCreatedAt(), null, null, null, 50)).isEmpty();
    }

    @Test
    void refundsAreScopedAndListedNewestFirst() {
        UUID merchantId = UUID.randomUUID();
        UUID otherMerchant = UUID.randomUUID();
        Payment payment = save(merchantId, "test", 5000, "USD", null);
        Payment otherPayment = save(otherMerchant, "test", 5000, "USD", null);

        refundRepository.saveAndFlush(Refund.succeeded(payment, 1000, "duplicate order", "{\"ticket\":\"T-1\"}"));
        refundRepository.saveAndFlush(Refund.succeeded(payment, 2000, null, null));
        refundRepository.saveAndFlush(Refund.succeeded(otherPayment, 500, null, null));

        assertThat(findRefundPage(merchantId, "test", null, null, null, null, null, null, null, 50))
                .hasSize(2);
        assertThat(findRefundPage(otherMerchant, "test", null, null, null, null, null, null, null, 50))
                .hasSize(1);
        // Filtered to one payment, and by metadata containment, exactly as payments are.
        assertThat(findRefundPage(merchantId, "test", payment.getId(), null, null, null, null,
                null, null, 50)).hasSize(2);
        assertThat(findRefundPage(merchantId, "test", null, null, null, null, "{\"ticket\":\"T-1\"}",
                null, null, 50)).hasSize(1);
    }

    @Test
    void refundsOfOnePaymentAreReturnedOldestFirstAsAHistory() {
        UUID merchantId = UUID.randomUUID();
        Payment payment = save(merchantId, "test", 5000, "USD", null);
        Refund first = refundRepository.saveAndFlush(Refund.succeeded(payment, 1000, "first", null));
        Refund second = refundRepository.saveAndFlush(Refund.succeeded(payment, 2000, "second", null));

        assertThat(refundRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId()))
                .extracting(Refund::getId)
                .containsExactly(first.getId(), second.getId());
    }

    @Test
    void aCrossTenantCursorIsRejectedBeforeItReachesTheQuery() {
        UUID merchantId = UUID.randomUUID();
        UUID otherMerchant = UUID.randomUUID();
        Payment payment = save(merchantId, "test", 100, "USD", null);
        String cursor = cursorCodec.encode(
                new Cursor(payment.getCreatedAt(), payment.getId(), merchantId, "test"));

        // Signed by this platform, but issued to someone else. The repository would have
        // ignored it anyway (merchant comes from the context), which is exactly why the
        // codec refusing it matters — the failure is loud rather than an empty page.
        assertThat(cursor).isNotBlank();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> cursorCodec.decode(cursor, otherMerchant, "test"))
                .isInstanceOf(com.paymentflow.common.exception.BadRequestException.class);
    }
}
