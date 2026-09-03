package dev.paymentflow;

import dev.paymentflow.internal.PageFetch;

import java.util.List;
import java.util.function.Function;

/**
 * An offset page — the older {@code PageResponse} shape, on the two endpoints D139 left alone
 * ({@code /v1/webhook_deliveries} and {@code /v1/test/decisions}). Unlike a {@link CursorPage} it
 * does report a total, because those endpoints genuinely return one.
 *
 * <p>Not constructed directly — a list method returns one.
 */
public final class OffsetPage<T> extends Page<T> {

    private final List<T> content;
    private final int page;
    private final int size;
    private final long totalElements;
    private final int totalPages;
    private final boolean hasMore;
    private final PageFetch fetch;
    private final Function<Object, T> mapper;

    public OffsetPage(List<T> content, int page, int size, long totalElements, int totalPages, boolean hasMore,
                      ResponseMeta meta, PageFetch fetch, Function<Object, T> mapper) {
        super(meta);
        this.content = List.copyOf(content);
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
        this.hasMore = hasMore;
        this.fetch = fetch;
        this.mapper = mapper;
    }

    @Override
    public List<T> items() {
        return content;
    }

    @Override
    public boolean hasMore() {
        return hasMore;
    }

    /** The zero-based index of this page. */
    public int page() {
        return page;
    }

    /** How many objects each page holds. */
    public int size() {
        return size;
    }

    /** How many objects match the query in total. */
    public long totalElements() {
        return totalElements;
    }

    /** How many pages the result set spans. */
    public int totalPages() {
        return totalPages;
    }

    @Override
    public OffsetPage<T> nextPage() {
        if (!hasMore) {
            return null;
        }
        return dev.paymentflow.internal.Paginator.offset(fetch.fetch(page + 1), fetch, mapper);
    }
}
