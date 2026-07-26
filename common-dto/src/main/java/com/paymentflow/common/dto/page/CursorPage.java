package com.paymentflow.common.dto.page;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The list envelope every <b>public</b> ({@code /v1}) list endpoint returns (M19,
 * D107). Cursor-based, not offset-based, and deliberately a separate type from
 * {@link PageResponse} rather than a replacement for it.
 *
 * <p><b>Why cursors here and offsets there.</b> A payments list is append-heavy and
 * constantly changing. Under concurrent inserts, offset pagination silently skips rows
 * (a new row shifts everything down, so page 2 re-shows what page 1 already returned,
 * and something falls through the gap) — for a financial list that is a correctness
 * bug, not a cosmetic one. A cursor names a *position in the data*, not a count of rows
 * to skip, so it is stable no matter what is inserted while a client paginates.
 * {@link PageResponse} is retained unchanged for the internal {@code /api/v1} tier and
 * for the pre-M19 endpoints that already ship it (D98 makes that tier freely
 * changeable, so it can migrate later if a real need appears; there is none today).
 *
 * <p><b>No total count, by design.</b> A cursor page cannot cheaply report one, and
 * reporting it would require a second full-table count on every request — the exact
 * unbounded query M19's own risk table warns about. {@code hasMore} answers the only
 * question a paginating client actually needs.
 *
 * <p>{@code object} is a constant discriminator (`"list"`), matching the convention
 * M18's event envelope established, so a client deserializing a heterogeneous response
 * can branch on a field rather than on the endpoint it called.
 *
 * @param <T> element type
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CursorPage<T>(
        String object,
        List<T> data,
        boolean hasMore,
        /**
         * The cursor to pass as {@code starting_after} to fetch the next page, or
         * {@code null} when {@code hasMore} is false. Opaque and signed — a client must
         * treat it as a token, never parse or construct one.
         */
        String nextCursor) {

    /** The discriminator carried by every list response. */
    public static final String OBJECT_TYPE = "list";

    public CursorPage {
        data = (data == null) ? List.of() : List.copyOf(data);
    }

    /**
     * Builds a page from a batch that may contain one extra element.
     *
     * <p>Callers are expected to query {@code limit + 1} rows: the presence of that
     * extra row is what determines {@code hasMore} without a count query, and it is
     * trimmed off here rather than by each caller. Doing it in one place is what keeps
     * "did we over-fetch by one?" from being a per-endpoint bug.
     *
     * @param fetched      up to {@code limit + 1} elements, in page order
     * @param limit        the page size the client asked for
     * @param cursorOf     produces the opaque cursor for the last element on this page
     */
    public static <T> CursorPage<T> of(List<T> fetched, int limit, java.util.function.Function<T, String> cursorOf) {
        boolean hasMore = fetched.size() > limit;
        List<T> data = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        String nextCursor = (hasMore && !data.isEmpty()) ? cursorOf.apply(data.getLast()) : null;
        return new CursorPage<>(OBJECT_TYPE, data, hasMore, nextCursor);
    }

    /** An empty page — no data, no more, no cursor. */
    public static <T> CursorPage<T> empty() {
        return new CursorPage<>(OBJECT_TYPE, List.of(), false, null);
    }
}
