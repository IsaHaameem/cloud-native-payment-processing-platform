package com.paymentflow.common.dto.http;

/**
 * The transport headers the public {@code /v1} tier speaks, named exactly once (M22.0).
 *
 * <p><b>Why these are here rather than beside the filters that write them.</b> Every one of
 * these headers is set by a gateway filter and read by an SDK, and until M22.0 each was
 * spelled as a literal in the filter that produced it and nowhere else. That is fine while
 * the only consumer is the filter's own test; it stops being fine the moment a published
 * document has to describe the same header and four hand-written SDKs have to read it. A
 * header name that exists in two places is a header name that can differ in two places, and
 * the failure is silent in the worst way — the document promises {@code RateLimit-Reset},
 * the gateway sends {@code X-RateLimit-Reset}, and the SDK's backoff quietly falls back to
 * its computed delay forever.
 *
 * <p>This is the same narrow exception D140 records for {@code CanonicalEventType}: the
 * platform's rule is schema-per-service (D4/D36), but a <em>frozen public contract that
 * several modules must render identically</em> belongs in {@code common-dto}, where exactly
 * one copy exists. These names are as frozen as the API gets — an integrator's retry loop is
 * written against them.
 *
 * <p><b>Where each is produced.</b> The gateway's filter order is load-bearing (§7), and it
 * decides which responses can carry which header — a rejection written by an early filter
 * never reaches the later ones:
 *
 * <table>
 *   <caption>Header origin by filter order</caption>
 *   <tr><th>Header</th><th>Written by</th><th>Order</th></tr>
 *   <tr><td>{@code X-Correlation-Id}</td><td>{@code CorrelationIdWebFilter}</td><td>HIGHEST</td></tr>
 *   <tr><td>{@link #RATE_LIMIT_LIMIT} and friends</td><td>{@code ApiKeyRateLimitWebFilter}</td><td>+30</td></tr>
 *   <tr><td>{@link #RETRY_AFTER}</td><td>{@code ApiKeyRateLimitWebFilter}, refusals only</td><td>+30</td></tr>
 *   <tr><td>{@link #VERSION}, {@link #DEPRECATION}, {@link #SUNSET}, {@link #LINK}</td>
 *       <td>{@code ApiVersionWebFilter}</td><td>+40</td></tr>
 * </table>
 *
 * <p>{@code X-Correlation-Id} is deliberately <em>not</em> duplicated here: it already has a
 * single home in {@code CorrelationConstants}, and it is not a public-tier concern — every
 * internal hop carries it too.
 */
public final class PublicApiHeaders {

    /**
     * The dated contract revision: sent by a caller to pin one request, echoed on every
     * response that reached the version filter.
     *
     * @see com.paymentflow.common.dto.version.ApiVersions
     */
    public static final String VERSION = "PaymentFlow-Version";

    /** The daily quota the key is measured against. */
    public static final String RATE_LIMIT_LIMIT = "RateLimit-Limit";

    /** How much of {@link #RATE_LIMIT_LIMIT} is left in the current window. */
    public static final String RATE_LIMIT_REMAINING = "RateLimit-Remaining";

    /** Seconds until the quota window resets — the value an SDK should sleep for, not guess. */
    public static final String RATE_LIMIT_RESET = "RateLimit-Reset";

    /**
     * Seconds to wait before retrying a refused request. Standard (RFC 9110) and spelled
     * identically by {@code org.springframework.http.HttpHeaders.RETRY_AFTER}; named here so
     * that the published document and the filter that sets it read from one declaration.
     */
    public static final String RETRY_AFTER = "Retry-After";

    /** RFC 8594. {@code true} when the revision that answered has been superseded. */
    public static final String DEPRECATION = "Deprecation";

    /** RFC 8594. The RFC 9110 date after which the superseded revision stops being served. */
    public static final String SUNSET = "Sunset";

    /** RFC 8288. Points at the versioning documentation with {@code rel="deprecation"}. */
    public static final String LINK = "Link";

    private PublicApiHeaders() {
    }
}
