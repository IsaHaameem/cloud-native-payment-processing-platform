package com.paymentflow.common.dto.page;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The list envelope's over-fetch trimming (M19.1). Centralised in {@code CursorPage.of}
 * precisely so "did we fetch one extra, and did we remember to trim it?" cannot become a
 * per-endpoint bug — an untrimmed page silently returns one row more than the client
 * asked for, and a mis-trimmed one drops a row from the sequence entirely.
 */
class CursorPageTest {

    private static String cursorOf(String element) {
        return "cursor-" + element;
    }

    @Test
    void anExtraFetchedRowMeansThereIsMoreAndIsTrimmedOff() {
        CursorPage<String> page = CursorPage.of(List.of("a", "b", "c"), 2, CursorPageTest::cursorOf);

        assertThat(page.data()).containsExactly("a", "b");
        assertThat(page.hasMore()).isTrue();
        // The cursor points at the last row actually returned, not the extra one — the
        // next page must begin after "b", not after "c".
        assertThat(page.nextCursor()).isEqualTo("cursor-b");
    }

    @Test
    void anExactlyFullPageIsNotReportedAsHavingMore() {
        // The boundary that an off-by-one gets wrong: exactly `limit` rows means the data
        // ended, and claiming otherwise sends the client after an empty page.
        CursorPage<String> page = CursorPage.of(List.of("a", "b"), 2, CursorPageTest::cursorOf);

        assertThat(page.data()).containsExactly("a", "b");
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void aPartialPageHasNoMoreAndNoCursor() {
        CursorPage<String> page = CursorPage.of(List.of("a"), 5, CursorPageTest::cursorOf);

        assertThat(page.data()).containsExactly("a");
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void anEmptyResultIsAWellFormedEmptyPage() {
        CursorPage<String> page = CursorPage.of(List.of(), 10, CursorPageTest::cursorOf);

        assertThat(page.data()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.object()).isEqualTo(CursorPage.OBJECT_TYPE);
    }

    @Test
    void everyPageCarriesTheListDiscriminator() {
        assertThat(CursorPage.of(List.of("a"), 1, CursorPageTest::cursorOf).object()).isEqualTo("list");
        assertThat(CursorPage.empty().object()).isEqualTo("list");
    }

    @Test
    void theDataListIsImmutable() {
        CursorPage<String> page = CursorPage.of(new java.util.ArrayList<>(List.of("a", "b")), 5,
                CursorPageTest::cursorOf);

        assertThat(page.data()).isUnmodifiable();
    }

    @Test
    void aNullDataListBecomesEmptyRatherThanNull() {
        // Defensive for the same reason PageResponse and ApiError are: a null collection
        // on the wire is a client-side NPE waiting to happen.
        assertThat(new CursorPage<String>("list", null, false, null).data()).isEmpty();
    }
}
