package com.paymentflow.common.dto.page;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageResponseTest {

    @Test
    void derivesTotalPagesAndFlagsForFirstPage() {
        PageResponse<String> page = PageResponse.of(List.of("a", "b"), 0, 2, 5);

        assertThat(page.totalPages()).isEqualTo(3); // ceil(5/2)
        assertThat(page.first()).isTrue();
        assertThat(page.last()).isFalse();
    }

    @Test
    void marksLastPageCorrectly() {
        PageResponse<String> page = PageResponse.of(List.of("e"), 2, 2, 5);

        assertThat(page.first()).isFalse();
        assertThat(page.last()).isTrue();
    }

    @Test
    void handlesEmptyResultSet() {
        PageResponse<String> page = PageResponse.of(List.of(), 0, 20, 0);

        assertThat(page.totalPages()).isZero();
        assertThat(page.first()).isTrue();
        assertThat(page.last()).isTrue();
        assertThat(page.content()).isEmpty();
    }

    @Test
    void contentIsImmutable() {
        PageResponse<String> page = PageResponse.of(List.of("a"), 0, 10, 1);

        assertThat(page.content()).isUnmodifiable();
    }
}
