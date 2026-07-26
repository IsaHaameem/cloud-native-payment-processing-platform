package com.paymentflow.audit.service;

import com.paymentflow.audit.domain.AuditLogEntry;
import com.paymentflow.audit.dto.EventResponse;
import com.paymentflow.audit.repository.AuditLogEntryRepository;
import com.paymentflow.common.dto.event.CanonicalEventType;
import com.paymentflow.common.dto.page.CursorPage;
import com.paymentflow.common.exception.BadRequestException;
import com.paymentflow.common.exception.ResourceNotFoundException;
import com.paymentflow.common.query.Cursor;
import com.paymentflow.common.query.CursorCodec;
import com.paymentflow.common.query.ListQuery;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The merchant-facing Events API (M19.5, §5/M19 task 4) — audit-service's first read
 * surface, projecting {@code audit_log} into the canonical {@code evt_} shape M18 defined.
 *
 * <p><b>Projection, not duplication.</b> The {@code evt_} id is derived from the stored
 * envelope id and the type name comes from the shared {@link CanonicalEventType}, so this
 * service produces byte-identical identifiers to the ones notification-service delivered
 * in a webhook body — without either service knowing the other exists. That determinism
 * was built into M18.3 for exactly this moment.
 *
 * <p>Only merchant-facing event types are ever returned. audit-service also records
 * {@code merchant.events} (key issuance, revocation), which are an operator's audit trail
 * rather than a merchant's event feed; they are excluded by construction because their
 * internal type has no canonical counterpart, not by a deny-list someone has to maintain.
 */
@Service
@Transactional(readOnly = true)
public class EventQueryService {

    /** Every canonical type's internal name — the "no filter" case, so the query needs no null guard. */
    private static final Set<String> ALL_MERCHANT_FACING = java.util.Arrays.stream(CanonicalEventType.values())
            .map(CanonicalEventType::internalEventType)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final AuditLogEntryRepository auditLogEntryRepository;
    private final CursorCodec cursorCodec;
    private final ObjectMapper objectMapper;

    public EventQueryService(AuditLogEntryRepository auditLogEntryRepository, CursorCodec cursorCodec,
                             ObjectMapper objectMapper) {
        this.auditLogEntryRepository = auditLogEntryRepository;
        this.cursorCodec = cursorCodec;
        this.objectMapper = objectMapper;
    }

    public EventResponse get(UUID merchantId, String mode, String eventRef) {
        UUID sourceEventId = parseEventRef(eventRef);
        return auditLogEntryRepository.findByEventIdAndMerchantIdAndMode(sourceEventId, merchantId, mode)
                // An internal-only event that happens to belong to this merchant is still
                // not a merchant-facing event, so it must 404 rather than leak.
                .filter(entry -> CanonicalEventType.fromInternal(entry.getEventType()).isPresent())
                .map(this::toResponse)
                .orElseThrow(() -> ResourceNotFoundException.of("Event", eventRef));
    }

    public CursorPage<EventResponse> list(UUID merchantId, String mode, ListQuery query, String typeFilter) {
        Set<String> types = resolveTypes(typeFilter);
        if (types.isEmpty()) {
            return CursorPage.empty();
        }

        List<AuditLogEntry> fetched = auditLogEntryRepository.findPage(merchantId, mode, types,
                query.createdAfterBound(), query.createdBeforeBound(),
                query.cursorCreatedAtBound(), query.cursorIdBound(),
                Limit.of(query.fetchSize()));

        CursorPage<AuditLogEntry> page = CursorPage.of(fetched, query.limit(),
                entry -> cursorCodec.encode(new Cursor(entry.getOccurredAt(), entry.getId(), merchantId, mode)));

        return new CursorPage<>(CursorPage.OBJECT_TYPE,
                page.data().stream().map(this::toResponse).toList(), page.hasMore(), page.nextCursor());
    }

    private EventResponse toResponse(AuditLogEntry entry) {
        String canonicalType = CanonicalEventType.fromInternal(entry.getEventType())
                .map(CanonicalEventType::canonicalName)
                .orElse(entry.getEventType());
        return new EventResponse(
                CanonicalEventType.eventRefFor(entry.getEventId()),
                EventResponse.OBJECT_TYPE,
                canonicalType,
                entry.getMode(),
                entry.getOccurredAt(),
                objectMapper.readTree(entry.getPayload()));
    }

    /** A canonical type name, or every type when unfiltered. Unknown names are a 400, not an empty page. */
    private static Set<String> resolveTypes(String typeFilter) {
        if (typeFilter == null || typeFilter.isBlank()) {
            return ALL_MERCHANT_FACING;
        }
        return CanonicalEventType.fromCanonical(typeFilter.trim().toLowerCase(Locale.ROOT))
                .map(type -> Set.of(type.internalEventType()))
                .orElseThrow(() -> new BadRequestException("Unknown event type: " + typeFilter
                        + ". Supported: " + CanonicalEventType.documentedVocabulary() + "."));
    }

    /**
     * Parses the public {@code evt_<32 hex>} form back to the envelope id it was derived
     * from. Deterministic in both directions, which is what lets a merchant hand back an
     * id they received in a webhook body and have this service find it.
     */
    private static UUID parseEventRef(String eventRef) {
        String hex = Optional.ofNullable(eventRef).orElse("");
        if (hex.startsWith(CanonicalEventType.ID_PREFIX)) {
            hex = hex.substring(CanonicalEventType.ID_PREFIX.length());
        }
        if (hex.length() != 32) {
            throw new BadRequestException("Not a valid event id.");
        }
        try {
            return UUID.fromString(hex.replaceFirst(
                    "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                    "$1-$2-$3-$4-$5"));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Not a valid event id.");
        }
    }
}
