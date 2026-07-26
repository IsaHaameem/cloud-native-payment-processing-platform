package com.paymentflow.common.query;

import com.paymentflow.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The parameter-shape half of the metadata filter (M19.8). The containment half is
 * proven against real Postgres in payment-service; what is at stake here is which
 * parameters count as part of the filter and, more importantly, which ones must not be
 * quietly discarded.
 */
class MetadataFilterParamsTest {

    @Test
    void bracketedParametersBecomeTheFilterAndEverythingElseIsIgnored() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("limit", "25");
        params.put("status", "CAPTURED");
        params.put("metadata[orderId]", "A-1234");
        params.put("metadata[channel]", "web");

        assertThat(MetadataFilterParams.from(params))
                .containsExactly(Map.entry("orderId", "A-1234"), Map.entry("channel", "web"));
    }

    @Test
    void noMetadataParameterMeansNoFilterRatherThanAnEmptyOne() {
        // The distinction matters downstream: an empty containment filter matches every
        // row, so "no filter" has to arrive as absent, not as {}.
        assertThat(MetadataFilterParams.from(Map.of("limit", "25"))).isEmpty();
        assertThat(MetadataFilterParams.from(Map.of())).isEmpty();
        assertThat(MetadataFilterParams.from(null)).isEmpty();
    }

    @Test
    void aBareMetadataParameterIsRejectedRatherThanDropped() {
        // This is the whole reason the class exists. `?metadata=A-1234` reads like a
        // filter; ignoring it would return every row the caller believed was excluded.
        assertThatThrownBy(() -> MetadataFilterParams.from(Map.of("metadata", "A-1234")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("metadata[key]=value");
    }

    @Test
    void aMalformedBracketedParameterIsRejectedRatherThanDropped() {
        assertThatThrownBy(() -> MetadataFilterParams.from(Map.of("metadata[]", "A-1234")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> MetadataFilterParams.from(Map.of("metadata[orderId", "A-1234")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> MetadataFilterParams.from(Map.of("metadata[ ]", "A-1234")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void aParameterMerelyStartingWithTheWordMetadataIsNotAFilter() {
        // `metadataOnly` is not `metadata`, and treating it as a malformed filter would
        // reject a parameter a future version might legitimately add.
        assertThat(MetadataFilterParams.from(Map.of("metadataOnly", "true"))).isEmpty();
    }

    @Test
    void anEmptyValueIsAFilterForAnEmptyValueNotAnAbsentFilter() {
        // `metadata[orderId]=` asks for rows whose orderId is the empty string. That will
        // match nothing in practice, which is the correct answer — and a very different
        // one from returning every row.
        assertThat(MetadataFilterParams.from(Map.of("metadata[orderId]", "")))
                .containsExactly(Map.entry("orderId", ""));
    }
}
