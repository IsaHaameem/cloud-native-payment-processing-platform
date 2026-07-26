package com.paymentflow.audit.repository;

import com.paymentflow.audit.domain.AuditLogEntry;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditLogEntryRepository extends JpaRepository<AuditLogEntry, UUID> {

    boolean existsByEventId(UUID eventId);

    /**
     * One event by its source id, scoped to the caller (M19.5).
     *
     * <p>Scoped by merchant and mode even though {@code eventId} is unique, because it is
     * a value a client supplies: resolving it unscoped would be an IDOR surface rather
     * than a lookup. A miss surfaces as 404, never 403 (D102).
     */
    Optional<AuditLogEntry> findByEventIdAndMerchantIdAndMode(UUID eventId, UUID merchantId, String mode);

    /**
     * The events list: keyset-paginated, newest first, restricted to merchant-facing
     * event types.
     *
     * <p>Ordered and keyed on {@code occurredAt} rather than {@code recordedAt}: a
     * merchant asking "what happened, in order" means the order things happened, not the
     * order this service got round to writing them down — and under Kafka redelivery
     * those two genuinely differ.
     *
     * <p>{@code eventTypes} is always a concrete collection, never null — "no type filter"
     * is expressed as "every canonical type". That gives one query shape with no null
     * guard, and has a useful side effect: a mode-less {@code merchant.events} row (an
     * {@code ApiKeyIssued}, say) can never appear in a merchant's event feed, because its
     * internal type is not in the canonical vocabulary at all. D126's decision to record
     * those honestly rather than coerce them to live is what makes that filter correct
     * rather than lucky.
     *
     * <p>Sentinel bounds rather than {@code :param is null} guards, for the same reason as
     * transaction-service's ledger page: Postgres cannot infer a bind parameter's type
     * from {@code ? is null} alone and rejects the statement outright. The constants live
     * in {@link com.paymentflow.common.query.ListQuery} since M19.8, which also recorded
     * the second reason to prefer them — the guarded form is a filter, not an index
     * condition.
     *
     * <p>M19.8's plan for this query is the one that needed no change:
     * {@code idx_audit_log_merchant_mode_occurred} serves merchant, mode <em>and</em> the
     * occurred-at range as a single index condition, with only the event-type vocabulary
     * left as a filter on rows already located.
     */
    @Query("""
            select a from AuditLogEntry a
            where a.merchantId = :merchantId
              and a.mode = :mode
              and a.eventType in :eventTypes
              and a.occurredAt >= :occurredAfter
              and a.occurredAt < :occurredBefore
              and (a.occurredAt < :cursorOccurredAt
                   or (a.occurredAt = :cursorOccurredAt and a.id < :cursorId))
            order by a.occurredAt desc, a.id desc
            """)
    List<AuditLogEntry> findPage(@Param("merchantId") UUID merchantId,
                                 @Param("mode") String mode,
                                 @Param("eventTypes") Collection<String> eventTypes,
                                 @Param("occurredAfter") Instant occurredAfter,
                                 @Param("occurredBefore") Instant occurredBefore,
                                 @Param("cursorOccurredAt") Instant cursorOccurredAt,
                                 @Param("cursorId") UUID cursorId,
                                 Limit limit);
}
