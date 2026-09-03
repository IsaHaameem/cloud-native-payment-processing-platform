package dev.paymentflow.internal;

import dev.paymentflow.CursorPage;
import dev.paymentflow.OffsetPage;
import dev.paymentflow.ResponseMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Turns a raw list envelope into a typed {@link CursorPage} or {@link OffsetPage}.
 *
 * <p>The envelope shapes are the contract's: a cursor page carries {@code data}/{@code hasMore}/
 * {@code nextCursor}, an offset page carries {@code content}/{@code page}/{@code totalPages}/
 * {@code last}. Where {@code hasMore} is absent the fallback is not {@code false} — a page with a
 * cursor and no flag plainly has more, and stopping there would silently truncate the result.
 */
public final class Paginator {

    private Paginator() {}

    @SuppressWarnings("unchecked")
    public static <T> CursorPage<T> cursor(Transport.Result result, PageFetch fetch, Function<Object, T> mapper) {
        Map<String, Object> body = result.data() instanceof Map ? (Map<String, Object>) result.data() : Map.of();
        List<T> items = mapList(body.get("data"), mapper);
        String nextCursor = body.get("nextCursor") instanceof String s ? s : null;
        Object flag = body.get("hasMore");
        boolean hasMore = flag instanceof Boolean b ? b : nextCursor != null;
        return new CursorPage<>(items, nextCursor, hasMore, result.meta(), fetch, mapper);
    }

    @SuppressWarnings("unchecked")
    public static <T> OffsetPage<T> offset(Transport.Result result, PageFetch fetch, Function<Object, T> mapper) {
        Map<String, Object> body = result.data() instanceof Map ? (Map<String, Object>) result.data() : Map.of();
        List<T> content = mapList(body.get("content"), mapper);
        int page = intValue(body.get("page"), 0);
        int size = intValue(body.get("size"), content.size());
        long totalElements = longValue(body.get("totalElements"), content.size());
        int totalPages = intValue(body.get("totalPages"), 0);
        Object last = body.get("last");
        boolean hasMore = last instanceof Boolean b ? !b : page + 1 < totalPages;
        return new OffsetPage<>(content, page, size, totalElements, totalPages, hasMore, result.meta(), fetch, mapper);
    }

    /** Metadata for a page fetched with no body yet — used only by tests that build a page by hand. */
    public static ResponseMeta emptyMeta() {
        return new ResponseMeta(200, null, null, null, false, null, 1);
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> mapList(Object raw, Function<Object, T> mapper) {
        if (!(raw instanceof List)) {
            return List.of();
        }
        List<T> out = new ArrayList<>();
        for (Object element : (List<Object>) raw) {
            out.add(mapper.apply(element));
        }
        return out;
    }

    private static int intValue(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private static long longValue(Object value, long fallback) {
        return value instanceof Number n ? n.longValue() : fallback;
    }
}
