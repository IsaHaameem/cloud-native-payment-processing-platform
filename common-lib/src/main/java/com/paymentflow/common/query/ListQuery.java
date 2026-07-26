package com.paymentflow.common.query;

import com.paymentflow.common.exception.BadRequestException;

import java.time.Instant;
import java.util.UUID;

/**
 * The parameters every public list endpoint accepts, resolved once (M19, task 7).
 *
 * <p>Exists so limit clamping, cursor decoding, and range validation are implemented in
 * one place rather than five. The failure this prevents is specific: five
 * hand-implemented copies drift, and the one that forgets to clamp {@code limit} is an
 * unbounded query over a table that only grows — M19's own risk table names exactly
 * that.
 *
 * <p>Resolved at the web layer, where the verified {@code MerchantContext} is available,
 * and passed down as a value object. Services never read request parameters
 * themselves.
 *
 * @param limit          page size, already clamped to {@link #MAX_LIMIT}
 * @param after          decoded cursor position, or {@code null} for the first page
 * @param createdAfter   inclusive lower bound on creation time, or {@code null}
 * @param createdBefore  exclusive upper bound on creation time, or {@code null}
 */
public record ListQuery(int limit, Cursor after, Instant createdAfter, Instant createdBefore) {

    /** The page size a client gets when it does not ask for one. */
    public static final int DEFAULT_LIMIT = 25;

    /**
     * The hard ceiling. Not advisory — a request for more is clamped, not rejected,
     * because failing a request for asking for too much teaches a client nothing it can
     * act on, whereas a short page plus {@code hasMore} is self-describing.
     */
    public static final int MAX_LIMIT = 100;

    /**
     * Lower sentinel for an absent {@code created_after}: no row predates the epoch.
     *
     * <p><b>Sentinels rather than nulls, and M19.8 turned that from a workaround into the
     * rule.</b> M19.4 first reached for them because Postgres cannot infer a bind
     * parameter's type from {@code ? is null} alone and rejects the statement outright.
     * Capturing {@code EXPLAIN} plans then showed the same choice has a second, larger
     * consequence: a predicate written as {@code (:bound is null or col >= :bound)} is a
     * <em>filter</em>, not an index condition, so Postgres scans the whole partition and
     * discards rows, while the unguarded form becomes part of the index range scan. On a
     * deep cursor page that was the difference between 2,512 and 29 buffers.
     *
     * <p>So these live here, beside {@link #DEFAULT_LIMIT} and {@link #MAX_LIMIT}, rather
     * than being redeclared per repository: they are part of how a list query is
     * expressed, and three private copies is how the fourth endpoint gets it wrong.
     */
    public static final Instant EARLIEST = Instant.EPOCH;

    /**
     * Upper sentinel for an absent {@code created_before}.
     *
     * <p>{@code Instant.MAX} would be the obvious choice and is outside
     * {@code timestamptz}'s range, so the bound is deliberately a representable one.
     */
    public static final Instant LATEST = Instant.parse("9999-12-31T23:59:59Z");

    /**
     * The id tiebreaker for an absent cursor. Only consulted when a row's timestamp
     * equals {@link #LATEST}, which no real row's does — the maximum value is chosen so
     * that if one somehow did, it would still be included rather than silently dropped.
     */
    public static final UUID LAST_ID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    public ListQuery {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must already be clamped to 1.." + MAX_LIMIT);
        }
        if (createdAfter != null && createdBefore != null && !createdAfter.isBefore(createdBefore)) {
            throw new BadRequestException("created_after must be earlier than created_before.");
        }
    }

    /**
     * Resolves raw request parameters into a validated query.
     *
     * @param rawLimit      the client's requested limit, or {@code null}
     * @param startingAfter the client's opaque cursor, or {@code null}/blank
     * @param codec         verifies and decodes {@code startingAfter}
     * @param merchantId    from the verified context — the cursor must match it
     * @param mode          from the verified context — the cursor must match it
     */
    public static ListQuery resolve(Integer rawLimit, String startingAfter, Instant createdAfter,
                                    Instant createdBefore, CursorCodec codec, UUID merchantId, String mode) {
        Cursor after = (startingAfter == null || startingAfter.isBlank())
                ? null
                : codec.decode(startingAfter, merchantId, mode);
        return new ListQuery(clamp(rawLimit), after, createdAfter, createdBefore);
    }

    /** How many rows to actually fetch: one more than asked, so {@code hasMore} needs no count query. */
    public int fetchSize() {
        return limit + 1;
    }

    /** The lower time bound to bind, never null — see {@link #EARLIEST}. */
    public Instant createdAfterBound() {
        return createdAfter == null ? EARLIEST : createdAfter;
    }

    /** The upper time bound to bind, never null — see {@link #LATEST}. */
    public Instant createdBeforeBound() {
        return createdBefore == null ? LATEST : createdBefore;
    }

    /**
     * The cursor timestamp to bind, never null. Absent means "start at the newest row",
     * which as a keyset predicate is "everything strictly before the end of time".
     */
    public Instant cursorCreatedAtBound() {
        return after == null ? LATEST : after.createdAt();
    }

    /** The cursor id tiebreaker to bind, never null — see {@link #LAST_ID}. */
    public UUID cursorIdBound() {
        return after == null ? LAST_ID : after.id();
    }

    private static int clamp(Integer rawLimit) {
        if (rawLimit == null) {
            return DEFAULT_LIMIT;
        }
        // A negative or zero limit is a client mistake with no sensible interpretation;
        // clamping it up to 1 would silently return a page nobody asked for.
        if (rawLimit < 1) {
            throw new BadRequestException("limit must be at least 1.");
        }
        return Math.min(rawLimit, MAX_LIMIT);
    }
}
