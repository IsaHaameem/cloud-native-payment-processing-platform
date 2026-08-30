package dev.paymentflow;

import dev.paymentflow.internal.PageFetch;

import java.util.List;
import java.util.function.Function;

/**
 * A cursor page — the M19 list shape. No total count, deliberately: counting would cost a second
 * full query on every request, and {@link #hasMore()} is the only question a paginating client
 * needs.
 *
 * <p>Not constructed directly — a list method returns one. The constructor is public only because
 * the SDK's own resource classes, in another package, build it.
 */
public final class CursorPage<T> extends Page<T> {

    private final List<T> items;
    private final String nextCursor;
    private final boolean hasMore;
    private final PageFetch fetch;
    private final Function<Object, T> mapper;

    public CursorPage(List<T> items, String nextCursor, boolean hasMore, ResponseMeta meta,
                      PageFetch fetch, Function<Object, T> mapper) {
        super(meta);
        this.items = List.copyOf(items);
        this.nextCursor = nextCursor;
        this.hasMore = hasMore;
        this.fetch = fetch;
        this.mapper = mapper;
    }

    @Override
    public List<T> items() {
        return items;
    }

    @Override
    public boolean hasMore() {
        return hasMore;
    }

    /** The cursor to pass as {@code starting_after} for the next page. {@code null} on the last. */
    public String nextCursor() {
        return nextCursor;
    }

    @Override
    public CursorPage<T> nextPage() {
        if (!hasMore || nextCursor == null) {
            return null;
        }
        return dev.paymentflow.internal.Paginator.cursor(fetch.fetch(nextCursor), fetch, mapper);
    }
}
