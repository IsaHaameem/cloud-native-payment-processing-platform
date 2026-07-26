package com.paymentflow.common.query;

import com.paymentflow.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Limit clamping and range validation (M19.1, task 7). Implemented once here precisely
 * so five endpoints cannot drift, and the case that matters most is the ceiling — a list
 * endpoint that forgets to clamp is an unbounded query over a table that only grows.
 */
class ListQueryTest {

    private final CursorCodec codec = new CursorCodec("test-only-cursor-secret");
    private final UUID merchantId = UUID.randomUUID();

    private ListQuery resolve(Integer limit, String cursor) {
        return ListQuery.resolve(limit, cursor, null, null, codec, merchantId, "test");
    }

    @Test
    void anAbsentLimitGetsTheDefault() {
        assertThat(resolve(null, null).limit()).isEqualTo(ListQuery.DEFAULT_LIMIT);
    }

    @Test
    void anExcessiveLimitIsClampedRatherThanRejected() {
        // Clamped, not 4xx: failing a request for asking for too much teaches a client
        // nothing actionable, whereas a short page plus hasMore is self-describing.
        assertThat(resolve(1_000_000, null).limit()).isEqualTo(ListQuery.MAX_LIMIT);
        assertThat(resolve(ListQuery.MAX_LIMIT + 1, null).limit()).isEqualTo(ListQuery.MAX_LIMIT);
        assertThat(resolve(ListQuery.MAX_LIMIT, null).limit()).isEqualTo(ListQuery.MAX_LIMIT);
    }

    @Test
    void aNonPositiveLimitIsRejected() {
        // No sensible interpretation exists — clamping up to 1 would return a page the
        // client did not ask for and cannot explain.
        assertThatThrownBy(() -> resolve(0, null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> resolve(-5, null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void fetchSizeIsAlwaysOneMoreThanTheLimit() {
        // The extra row is what determines hasMore without a count query — the whole
        // reason CursorPage carries no total.
        assertThat(resolve(10, null).fetchSize()).isEqualTo(11);
        assertThat(resolve(null, null).fetchSize()).isEqualTo(ListQuery.DEFAULT_LIMIT + 1);
    }

    @Test
    void anAbsentOrBlankCursorMeansTheFirstPage() {
        assertThat(resolve(null, null).after()).isNull();
        assertThat(resolve(null, "").after()).isNull();
        assertThat(resolve(null, "   ").after()).isNull();
    }

    @Test
    void aSuppliedCursorIsDecodedAndBoundToTheCaller() {
        UUID rowId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-01T12:00:00Z");
        String cursor = codec.encode(new Cursor(createdAt, rowId, merchantId, "test"));

        ListQuery query = resolve(null, cursor);

        assertThat(query.after()).isNotNull();
        assertThat(query.after().id()).isEqualTo(rowId);
        assertThat(query.after().createdAt()).isEqualTo(createdAt);
    }

    @Test
    void anInvertedCreatedRangeIsRejected() {
        Instant later = Instant.parse("2026-08-02T00:00:00Z");
        Instant earlier = Instant.parse("2026-08-01T00:00:00Z");

        assertThatThrownBy(() -> ListQuery.resolve(null, null, later, earlier, codec, merchantId, "test"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("created_after");
        // Equal bounds select nothing, which is also a mistake rather than a query.
        assertThatThrownBy(() -> ListQuery.resolve(null, null, later, later, codec, merchantId, "test"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void aValidRangeIsAccepted() {
        Instant earlier = Instant.parse("2026-08-01T00:00:00Z");
        Instant later = Instant.parse("2026-08-02T00:00:00Z");

        ListQuery query = ListQuery.resolve(null, null, earlier, later, codec, merchantId, "test");

        assertThat(query.createdAfter()).isEqualTo(earlier);
        assertThat(query.createdBefore()).isEqualTo(later);
    }

    @Test
    void eitherBoundAloneIsAccepted() {
        Instant bound = Instant.parse("2026-08-01T00:00:00Z");

        assertThat(ListQuery.resolve(null, null, bound, null, codec, merchantId, "test").createdBefore()).isNull();
        assertThat(ListQuery.resolve(null, null, null, bound, codec, merchantId, "test").createdAfter()).isNull();
    }
}
