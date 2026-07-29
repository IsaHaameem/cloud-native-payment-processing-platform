package com.paymentflow.common.openapi;

/**
 * The descriptions of the query parameters that mean the same thing on every public list
 * (M21.7, D154).
 *
 * <p><b>Why these are constants in common-lib rather than prose on each handler.</b> Five
 * lists across four services take {@code limit}, {@code starting_after},
 * {@code created_after} and {@code created_before}, and they are not merely similar — they
 * are the *same contract*, described in {@code docs/READ_APIS.md} once and implemented once
 * in {@code ListQuery}. Written out per handler they would be twenty paragraphs that start
 * identical and end up saying four different things about the same cursor, which is exactly
 * the drift §5.0 standing rule 4 exists to prevent. An annotation attribute has to be a
 * compile-time constant, so they live here as {@code static final String}.
 *
 * <p>Parameters that genuinely differ per endpoint — {@code status} on payments,
 * {@code type} on events, {@code route} on usage — stay on their own handler, because there
 * the differences are the point.
 */
public final class PublicApiParameters {

    private PublicApiParameters() {
    }

    public static final String LIMIT = """
            How many objects to return, 1–100. Values above the ceiling are clamped to it \
            rather than rejected, so a client asking for more simply receives the maximum. \
            Defaults to 25.""";

    public static final String STARTING_AFTER = """
            A cursor for pagination. Pass the `nextCursor` from the previous response to \
            fetch the following page. The value is opaque and signed — treat it as a token \
            and never parse or construct one.""";

    public static final String CREATED_AFTER = """
            Return only objects created at or after this instant, as RFC 3339 \
            (`2026-07-29T00:00:00Z`).""";

    public static final String CREATED_BEFORE = """
            Return only objects created at or before this instant, as RFC 3339 \
            (`2026-07-29T23:59:59Z`).""";

    /**
     * <b>Required</b>, and the document said otherwise until M21.7's contract tests made a
     * real call without it and got a `400`. payment-service demands it on every mutation —
     * the Spring-level {@code required = false} is what lets the service produce a
     * catalogued `BAD_REQUEST` instead of Spring's own unmapped error, not a statement that
     * the header is optional.
     */
    public static final String IDEMPOTENCY_KEY = """
            A unique key that makes this request safe to retry. **Required on every mutating \
            request.** Replaying the same key returns the original response instead of \
            performing the operation twice; a second request arriving while the first is \
            still in flight fails fast with `409 IDEMPOTENCY_CONFLICT`. Use a fresh UUID per \
            logical operation.""";

    public static final String METADATA_FILTER = """
            Filter by metadata, spelled `metadata[key]=value` and repeatable. Matching is \
            containment and every named key must match: \
            `?metadata[orderId]=A-1234&metadata[channel]=web` returns only objects carrying \
            both. A key nothing carries selects nothing rather than everything.""";

    public static final String METADATA_FIELD = """
            Up to 20 arbitrary key-value pairs you can attach to this object. PaymentFlow \
            never interprets them; they are returned on every read and are filterable with \
            `metadata[key]=value`.""";

    /**
     * A response description rather than a parameter one, kept here because it belongs to
     * the same shared list contract and is used by five operations across four services.
     *
     * <p>Added in M21.7 because the contract tests sent a tampered cursor and got a `400`
     * that no list operation documented — the failure every cursor-paginated endpoint can
     * return, described by none of them.
     */
    public static final String INVALID_LIST_QUERY = """
            The query could not be understood: a tampered or malformed `starting_after` \
            cursor, a `limit` that is not positive, or a filter value outside the vocabulary \
            this endpoint accepts. A rejected filter is deliberately an error rather than an \
            empty page — an empty page is something you would then have to explain to \
            yourself.""";

    public static final String PAGE = """
            The zero-based page number to return. This endpoint predates cursor pagination \
            and keeps offset paging deliberately (D139).""";

    public static final String SIZE = "How many objects each page holds.";
}
