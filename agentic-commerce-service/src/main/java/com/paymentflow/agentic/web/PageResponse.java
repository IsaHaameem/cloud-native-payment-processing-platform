package com.paymentflow.agentic.web;

import java.util.List;
import java.util.function.Function;

/**
 * The list envelope every {@code /api/agentic/**} listing returns.
 *
 * <h2>Page-number, not cursor</h2>
 *
 * <p>The published {@code /v1} API is cursor-paginated (D107/D139) because its tables are
 * unbounded and a cursor is the only stable way to page a feed still being written to. The
 * agentic surface is different in kind: a demonstration layer over demo-sized data — tens of
 * products, a handful of conversations — reached only through the authenticated portal.
 * Page-number pagination over that maps directly onto Spring Data's {@code PageRequest}, gives
 * the caller an exact {@code total}, and needs no opaque token. If any listing here ever backs a
 * real high-volume feed, this is the type to revisit.
 *
 * @param data     the page, in the listing's declared order
 * @param page     zero-based page index that was applied (already clamped)
 * @param limit    page size that was applied (already clamped, 1..100)
 * @param total    total matching rows, from a {@code count} query
 * @param hasMore  whether a further page exists
 */
public record PageResponse<T>(List<T> data, int page, int limit, long total, boolean hasMore) {

    public static int clampLimit(Integer requested) {
        return Math.clamp(requested == null ? 50 : requested, 1, 100);
    }

    public static int clampPage(Integer requested) {
        return Math.max(0, requested == null ? 0 : requested);
    }

    public static <S, T> PageResponse<T> of(List<S> rows, int page, int limit, long total,
                                            Function<S, T> mapper) {
        boolean hasMore = (long) (page + 1) * limit < total;
        return new PageResponse<>(rows.stream().map(mapper).toList(), page, limit, total, hasMore);
    }
}
