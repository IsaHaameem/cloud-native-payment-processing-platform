package com.paymentflow.gateway.version;

import com.paymentflow.common.dto.version.ApiVersion;
import com.paymentflow.common.dto.version.ApiVersions;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code 2026-08-01} revision: payment and refund {@code status} values became lowercase
 * {@code snake_case} on the wire (M21.5).
 *
 * <p><b>What changed and why.</b> {@code AUTHORIZED}, {@code PARTIALLY_REFUNDED} and
 * {@code SUCCEEDED} were the Java enum constants leaking through Jackson's default
 * serialization, not a considered wire form. This platform had already decided that enum
 * *values* are lowercase {@code snake_case} on the wire when M21.4 introduced
 * {@code ErrorType} ({@code authentication_error}, {@code rate_limit_error}); the payment
 * resources were the only place that was not true, and freezing them into the published
 * baseline would have made the inconsistency permanent.
 *
 * <p><b>Why this is the revision that proves the machinery.</b> It is deliberately the
 * smallest change that exercises both directions. {@code status} is a response field on
 * three resources <em>and</em> a filter on two list endpoints, so a caller pinned to
 * {@code 2026-07-27} needs their query parameter translated forward and their response body
 * translated back. A revision that only changed a response would have left half the
 * transformation interface untested by anything real.
 *
 * <p><b>The transformation is structural, not a lookup table.</b> It uppercases whatever
 * value it finds rather than mapping known statuses to known statuses, which means a status
 * added in a later milestone is translated correctly without anyone remembering to extend
 * this class. The inverse holds for requests. That is the same reasoning the approved
 * decision applied to the layer as a whole: no per-endpoint special cases, and therefore
 * nothing to keep in step.
 */
@Component
public class StatusCaseTransformation implements ApiTransformation {

    /** The field this revision changed. Present on payments, refunds, and their lists. */
    private static final String STATUS = "status";

    /**
     * The envelope keys a list response nests its objects under. {@code data} is M19's
     * cursor envelope; {@code content} is the offset {@code PageResponse} that
     * {@code /v1/webhook_deliveries} and {@code /v1/test/decisions} still use (D139).
     * Neither of those two carries a payment status today — but naming both here costs
     * nothing and means the transformation does not quietly stop working if one ever does.
     */
    private static final List<String> ENVELOPE_KEYS = List.of("data", "content");

    @Override
    public ApiVersion appliesFrom() {
        return ApiVersions.V2026_08_01;
    }

    @Override
    public String description() {
        return "Payment and refund `status` values are lowercase snake_case "
                + "(`authorized`) rather than upper case (`AUTHORIZED`).";
    }

    /**
     * {@code ?status=AUTHORIZED} → {@code ?status=authorized}.
     *
     * <p>Old → current: the caller filters using the vocabulary their revision published, and
     * the service only understands the current one. Without this, a pinned caller's filter
     * would silently match nothing — the worst possible failure for a list endpoint, because
     * an empty page is a valid response and nothing would look broken.
     */
    @Override
    public MultiValueMap<String, String> transformRequestParams(String path,
                                                                MultiValueMap<String, String> queryParams) {
        if (!queryParams.containsKey(STATUS)) {
            return queryParams;
        }
        MultiValueMap<String, String> rewritten = new LinkedMultiValueMap<>(queryParams);
        rewritten.put(STATUS, queryParams.get(STATUS).stream()
                .map(value -> value == null ? null : value.toLowerCase(Locale.ROOT))
                .toList());
        return rewritten;
    }

    /**
     * Current → old, over whatever shape the body happens to be: a bare object, a cursor or
     * offset envelope, or a bare array.
     */
    @Override
    public JsonNode transformResponseBody(String path, JsonNode body) {
        if (body == null || body.isMissingNode()) {
            return body;
        }
        upperCaseStatuses(body);
        return body;
    }

    /**
     * Walks the response and uppercases every {@code status} string it owns.
     *
     * <p>Deliberately <em>not</em> a blind recursive walk over every {@code status} anywhere
     * in the tree. A webhook delivery has a {@code status} of its own
     * ({@code PENDING}/{@code DELIVERED}) that this revision did not change, and a delivery's
     * nested {@code attempts} have an {@code outcome}. Rewriting those would corrupt
     * resources the revision never touched. The walk is therefore scoped: the top-level
     * object, the objects directly inside a list envelope, and the elements of a bare array —
     * which is exactly "the resources this response is about" and never their sub-objects.
     */
    private void upperCaseStatuses(JsonNode body) {
        if (body.isArray()) {
            body.forEach(this::upperCaseStatusOn);
            return;
        }
        if (!body.isObject()) {
            return;
        }

        upperCaseStatusOn(body);

        for (String envelopeKey : ENVELOPE_KEYS) {
            JsonNode envelope = body.get(envelopeKey);
            if (envelope != null && envelope.isArray()) {
                envelope.forEach(this::upperCaseStatusOn);
            }
        }
    }

    /** Uppercases {@code status} on one object, if it has one and it is a string. */
    private void upperCaseStatusOn(JsonNode node) {
        if (!(node instanceof ObjectNode object)) {
            return;
        }
        JsonNode status = object.get(STATUS);
        if (status != null && status.isString()) {
            object.put(STATUS, status.stringValue().toUpperCase(Locale.ROOT));
        }
    }

    /** The status vocabulary this revision renamed, for documentation and tests. */
    public static Map<String, String> renamedValues() {
        return Map.of(
                "created", "CREATED",
                "authorized", "AUTHORIZED",
                "captured", "CAPTURED",
                "partially_refunded", "PARTIALLY_REFUNDED",
                "refunded", "REFUNDED",
                "failed", "FAILED",
                "voided", "VOIDED",
                "succeeded", "SUCCEEDED");
    }
}
