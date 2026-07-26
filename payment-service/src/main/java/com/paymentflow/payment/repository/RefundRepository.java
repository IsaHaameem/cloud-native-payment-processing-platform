package com.paymentflow.payment.repository;

import com.paymentflow.payment.domain.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every read is scoped by {@code merchantId} and {@code mode} in the signature (D101),
 * including the ones that look like they are keyed by something unique — a refund id is
 * a UUID a caller supplies, so resolving it without the scope would be an IDOR surface
 * rather than a lookup.
 */
public interface RefundRepository extends JpaRepository<Refund, UUID> {

    Optional<Refund> findByIdAndMerchantIdAndMode(UUID id, UUID merchantId, String mode);

    /** {@code expand=refunds} for a single payment — oldest first, so it reads as a history. */
    List<Refund> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);

    /** {@code expand=refunds} across a page of payments: one query, not one per row. */
    List<Refund> findByPaymentIdInOrderByCreatedAtAsc(Collection<UUID> paymentIds);

    /**
     * The public refunds list. Same keyset shape and the same native-query rationale as
     * {@code PaymentRepository.findPage} — see that method's javadoc for why jsonb
     * containment and row-wise comparison require native SQL, and for why the time and
     * cursor bounds are unguarded while the remaining filters are not.
     */
    @Query(value = """
            select * from payment.refunds r
            where r.merchant_id = :merchantId
              and r.mode = :mode
              and (cast(:paymentId as uuid) is null or r.payment_id = cast(:paymentId as uuid))
              and (cast(:status as varchar) is null or r.status = cast(:status as varchar))
              and r.created_at >= cast(:createdAfter as timestamptz)
              and r.created_at < cast(:createdBefore as timestamptz)
              and (cast(:metadata as jsonb) is null or r.metadata @> cast(:metadata as jsonb))
              and (r.created_at, r.id) < (cast(:cursorCreatedAt as timestamptz), cast(:cursorId as uuid))
            order by r.created_at desc, r.id desc
            limit :fetchSize
            """, nativeQuery = true)
    List<Refund> findPage(@Param("merchantId") UUID merchantId,
                          @Param("mode") String mode,
                          @Param("paymentId") UUID paymentId,
                          @Param("status") String status,
                          @Param("createdAfter") Instant createdAfter,
                          @Param("createdBefore") Instant createdBefore,
                          @Param("metadata") String metadata,
                          @Param("cursorCreatedAt") Instant cursorCreatedAt,
                          @Param("cursorId") UUID cursorId,
                          @Param("fetchSize") int fetchSize);
}
