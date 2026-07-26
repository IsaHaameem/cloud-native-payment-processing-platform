package com.paymentflow.payment.repository;

import com.paymentflow.payment.domain.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdAndMerchantIdAndMode(UUID id, UUID merchantId, String mode);

    /** V1's offset list, still serving the internal {@code /api/v1} tier (D98). Untouched by M19. */
    Page<Payment> findByMerchantIdAndMode(UUID merchantId, String mode, Pageable pageable);

    /**
     * The public list (M19.2): keyset pagination with the full filter set.
     *
     * <p><b>This is the platform's first native query, and the exception is
     * deliberate.</b> Two things here cannot be expressed in JPQL:
     * <ul>
     *   <li>{@code metadata @> :metadata} — jsonb containment, which is what makes the
     *       metadata filter use the GIN index this milestone's migration adds. JPQL has
     *       no jsonb operator, and the alternatives are a Hibernate function registration
     *       or a Criteria/Specification builder — both more machinery than one readable
     *       query, for a filter that will never be anything but containment.</li>
     *   <li>{@code (created_at, id) < (:cursorCreatedAt, :cursorId)} — row-wise
     *       comparison, which lets Postgres satisfy the keyset predicate directly from
     *       {@code idx_payments_merchant_mode_created} as a single index range scan.</li>
     * </ul>
     *
     * <p><b>The time and cursor bounds are unguarded, and that is what makes the previous
     * paragraph true.</b> This query originally wrapped each of them in
     * {@code (:bound is null or …)} so one shape could serve a request that supplied them
     * and one that did not. M19.8 captured the plan and found that guard demotes the
     * predicate from an index condition to a filter: Postgres scanned the merchant's whole
     * partition from the newest row and discarded everything above the cursor, making a
     * deep page cost O(depth) — precisely what keyset pagination exists to avoid. Measured
     * on 600k seeded payments, one page 150 days in went from <b>2,512 buffers to 29</b>.
     * The bounds now always carry a value ({@link com.paymentflow.common.query.ListQuery}'s
     * sentinels), so every one of them lands in the {@code Index Cond}.</p>
     *
     * <p>The remaining filters — status, currency, amount, metadata — keep their null
     * guards deliberately. They do not participate in the ordering, so the index still
     * supplies the sort and they cost only a {@code Filter} on rows already located; there
     * is no sentinel for "any status" that would not be a lie.</p>
     *
     * <p>The schema is qualified explicitly ({@code payment.payments}): Hibernate's
     * {@code default_schema} applies to entity mappings, not to native SQL, and the JDBC
     * {@code search_path} does not include it. The schema name is already fixed in
     * {@code application.yaml}, so this hardcodes nothing that was previously free.
     *
     * <p>Every filter is null-guarded so one query serves every combination rather than a
     * Specification tree assembling a different one per request. The {@code cast(:x as …)}
     * wrappers are required because Postgres cannot infer a bind parameter's type from
     * {@code :x is null} alone.
     *
     * <p>{@code merchantId} and {@code mode} are always bound from the verified context
     * and are not optional — there is no code path that can produce an unscoped query
     * (D101).
     */
    @Query(value = """
            select * from payment.payments p
            where p.merchant_id = :merchantId
              and p.mode = :mode
              and (cast(:status as varchar) is null or p.status = cast(:status as varchar))
              and (cast(:currency as varchar) is null or p.currency = cast(:currency as varchar))
              and (cast(:amountMin as bigint) is null or p.amount_minor >= cast(:amountMin as bigint))
              and (cast(:amountMax as bigint) is null or p.amount_minor <= cast(:amountMax as bigint))
              and p.created_at >= cast(:createdAfter as timestamptz)
              and p.created_at < cast(:createdBefore as timestamptz)
              and (cast(:metadata as jsonb) is null or p.metadata @> cast(:metadata as jsonb))
              and (p.created_at, p.id) < (cast(:cursorCreatedAt as timestamptz), cast(:cursorId as uuid))
            order by p.created_at desc, p.id desc
            limit :fetchSize
            """, nativeQuery = true)
    List<Payment> findPage(@Param("merchantId") UUID merchantId,
                           @Param("mode") String mode,
                           @Param("status") String status,
                           @Param("currency") String currency,
                           @Param("amountMin") Long amountMin,
                           @Param("amountMax") Long amountMax,
                           @Param("createdAfter") Instant createdAfter,
                           @Param("createdBefore") Instant createdBefore,
                           @Param("metadata") String metadata,
                           @Param("cursorCreatedAt") Instant cursorCreatedAt,
                           @Param("cursorId") UUID cursorId,
                           @Param("fetchSize") int fetchSize);
}
