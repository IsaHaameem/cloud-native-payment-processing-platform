package com.paymentflow.gateway.version;

import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code 2026-08-01} revision's transformation, in both directions (M21.5).
 *
 * <p>§5/M21's testing strategy asks for "transformer correctness in both directions", and
 * the cases that matter are the ones where a naive implementation would over-reach: a
 * response contains several objects with a {@code status} field and only some of them belong
 * to this revision.
 */
class StatusCaseTransformationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final StatusCaseTransformation transformation = new StatusCaseTransformation();

    private JsonNode transform(String json) {
        return transformation.transformResponseBody("/v1/payments", JSON.readTree(json));
    }

    // ── Response: current → old ──────────────────────────────────────────────────────

    @Test
    void aBarePaymentHasItsStatusUpperCased() {
        assertThat(transform("""
                {"id": "p_1", "object": "payment", "status": "authorized"}""")
                .path("status").stringValue()).isEqualTo("AUTHORIZED");
    }

    @Test
    void aMultiWordStatusKeepsItsUnderscore() {
        // `partially_refunded` -> `PARTIALLY_REFUNDED`, not `PARTIALLYREFUNDED`. The old
        // wire form was the Java constant name, which is SCREAMING_SNAKE.
        assertThat(transform("""
                {"status": "partially_refunded"}""").path("status").stringValue())
                .isEqualTo("PARTIALLY_REFUNDED");
    }

    @Test
    void everyObjectInACursorPageIsTransformed() {
        JsonNode page = transform("""
                {"object": "list",
                 "data": [{"id": "p_1", "status": "authorized"},
                          {"id": "p_2", "status": "captured"}],
                 "has_more": false}""");

        assertThat(page.path("data").get(0).path("status").stringValue()).isEqualTo("AUTHORIZED");
        assertThat(page.path("data").get(1).path("status").stringValue()).isEqualTo("CAPTURED");
        assertThat(page.path("has_more").booleanValue()).isFalse();
    }

    @Test
    void theOffsetEnvelopeIsHandledToo() {
        // /v1/webhook_deliveries and /v1/test/decisions still use PageResponse (D139).
        assertThat(transform("""
                {"content": [{"status": "refunded"}], "page": 0}""")
                .path("content").get(0).path("status").stringValue()).isEqualTo("REFUNDED");
    }

    @Test
    void aBareArrayIsHandled() {
        assertThat(transform("""
                [{"status": "voided"}, {"status": "failed"}]""")
                .get(0).path("status").stringValue()).isEqualTo("VOIDED");
    }

    @Test
    void nestedSubObjectsAreDeliberatelyNotTouched() {
        // The assertion with teeth. A webhook delivery has its own `status` (PENDING /
        // DELIVERED) that this revision never changed, and its `attempts` have an
        // `outcome`. A blind recursive walk over every `status` in the tree would corrupt
        // resources the revision does not own — and the corruption would only be visible to
        // a caller pinned to the old revision, which is the hardest place to notice it.
        JsonNode body = transform("""
                {"status": "captured",
                 "delivery": {"status": "delivered"},
                 "attempts": [{"status": "pending"}]}""");

        assertThat(body.path("status").stringValue()).isEqualTo("CAPTURED");
        assertThat(body.path("delivery").path("status").stringValue()).isEqualTo("delivered");
        assertThat(body.path("attempts").get(0).path("status").stringValue()).isEqualTo("pending");
    }

    @Test
    void aBodyWithNoStatusIsUnchanged() {
        assertThat(transform("""
                {"object": "balance", "available": 1000}""").path("available").intValue())
                .isEqualTo(1000);
    }

    @Test
    void aNonStringStatusIsLeftAlone() {
        // Defensive: a future resource could use a numeric or object `status`, and coercing
        // it to a string would be worse than ignoring it.
        assertThat(transform("""
                {"status": 42}""").path("status").intValue()).isEqualTo(42);
    }

    @Test
    void anErrorBodyIsUnaffected() {
        // ApiError has no `status` string — its `status` is the numeric HTTP code, so this
        // doubles as a check that the transformation cannot damage an error response.
        assertThat(transform("""
                {"status": 404, "code": "NOT_FOUND", "type": "invalid_request_error"}""")
                .path("code").stringValue()).isEqualTo("NOT_FOUND");
    }

    @Test
    void theTransformationIsStructuralRatherThanALookupTable() {
        // A status this revision has never heard of is still translated correctly, which is
        // what keeps the transformation from needing an edit every time a status is added.
        assertThat(transform("""
                {"status": "requires_action"}""").path("status").stringValue())
                .isEqualTo("REQUIRES_ACTION");
    }

    // ── Request: old → current ───────────────────────────────────────────────────────

    @Test
    void theStatusFilterIsLowerCasedOnTheWayIn() {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("status", "AUTHORIZED");
        params.add("limit", "20");

        MultiValueMap<String, String> rewritten =
                transformation.transformRequestParams("/v1/payments", params);

        assertThat(rewritten.getFirst("status")).isEqualTo("authorized");
        assertThat(rewritten.getFirst("limit")).isEqualTo("20");
    }

    @Test
    void aRequestWithNoStatusFilterIsReturnedUntouched() {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("limit", "20");

        assertThat(transformation.transformRequestParams("/v1/payments", params)).isSameAs(params);
    }

    @Test
    void repeatedStatusParametersAreAllRewritten() {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.addAll("status", java.util.List.of("AUTHORIZED", "CAPTURED"));

        assertThat(transformation.transformRequestParams("/v1/payments", params).get("status"))
                .containsExactly("authorized", "captured");
    }

    @Test
    void theInputMapIsNotMutated() {
        // The exchange's query params are shared state; rewriting them in place would leak
        // the transformed value into the request log and anything else reading the exchange.
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("status", "AUTHORIZED");

        transformation.transformRequestParams("/v1/payments", params);

        assertThat(params.getFirst("status")).isEqualTo("AUTHORIZED");
    }

    @Test
    void aRoundTripReturnsTheOriginalVocabulary() {
        // Old vocabulary in on the request, old vocabulary out on the response: the whole
        // compatibility promise, asserted end to end at the transformer level.
        StatusCaseTransformation.renamedValues().forEach((current, old) -> {
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("status", old);

            assertThat(transformation.transformRequestParams("/v1/payments", params).getFirst("status"))
                    .isEqualTo(current);
            assertThat(transform("{\"status\": \"" + current + "\"}").path("status").stringValue())
                    .isEqualTo(old);
        });
    }
}
