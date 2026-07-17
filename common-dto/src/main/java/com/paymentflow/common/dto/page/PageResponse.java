package com.paymentflow.common.dto.page;

import java.util.List;

/**
 * Immutable, framework-agnostic pagination envelope so list endpoints expose a
 * consistent shape without leaking Spring Data's {@code Page} type onto the wire.
 *
 * @param <T> element type
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public PageResponse {
        content = (content == null) ? List.of() : List.copyOf(content);
    }

    /**
     * Builds a page from raw values, deriving {@code totalPages}, {@code first} and
     * {@code last}.
     *
     * @param content       elements on the current page
     * @param page          zero-based page index
     * @param size          requested page size
     * @param totalElements total number of elements across all pages
     */
    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = (size <= 0) ? 0 : (int) Math.ceil((double) totalElements / (double) size);
        boolean first = page <= 0;
        boolean last = page >= totalPages - 1;
        return new PageResponse<>(content, page, size, totalElements, totalPages, first, last);
    }
}
