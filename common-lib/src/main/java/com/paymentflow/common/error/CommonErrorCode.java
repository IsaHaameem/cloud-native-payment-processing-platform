package com.paymentflow.common.error;

import com.paymentflow.common.dto.error.ErrorType;

/**
 * Generic, cross-cutting error codes reused by every service. Domain-specific codes
 * belong to the owning service, not here.
 *
 * <p>Each code now also declares its {@link ErrorType} (M21.4). The mapping is not
 * mechanical from the HTTP status, which is exactly why it is stated per code rather than
 * derived: {@code CONFLICT} and {@code IDEMPOTENCY_CONFLICT} are both 409 and belong to
 * different types, because one means "this resource is not in a state where that is legal"
 * and the other means "you reused a key" — and §7.1's SDKs may retry the second while never
 * retrying the first.
 */
public enum CommonErrorCode implements ErrorCode {

    VALIDATION_FAILED(400, ErrorType.INVALID_REQUEST_ERROR, "One or more fields are invalid."),
    BAD_REQUEST(400, ErrorType.INVALID_REQUEST_ERROR, "The request could not be understood."),
    /**
     * The {@code PaymentFlow-Version} header names a revision this platform does not serve —
     * a malformed date, a typo, or one that has passed its sunset (M21.5, §4.10). A client
     * error rather than a fallback: silently answering in a different revision than the one
     * asked for would give a caller a shape they did not request and no way to notice.
     */
    UNSUPPORTED_API_VERSION(400, ErrorType.INVALID_REQUEST_ERROR,
            "The requested API version is not supported."),
    UNAUTHORIZED(401, ErrorType.AUTHENTICATION_ERROR, "Authentication is required or has failed."),
    FORBIDDEN(403, ErrorType.PERMISSION_ERROR, "You do not have permission to perform this action."),
    /**
     * The key is valid but lacks the scope this route requires, or is a publishable key
     * attempting a mutation (§4.3). Emitted by the gateway, which is the only thing that
     * knows a request's required scope. Catalogued in M21.4: it was a string literal in
     * {@code ApiKeyAuthenticationWebFilter} before, which meant the platform published a
     * code the catalogue did not list.
     */
    INSUFFICIENT_SCOPE(403, ErrorType.PERMISSION_ERROR,
            "This API key does not have the required scope for this endpoint."),
    NOT_FOUND(404, ErrorType.INVALID_REQUEST_ERROR, "The requested resource was not found."),
    CONFLICT(409, ErrorType.INVALID_REQUEST_ERROR,
            "The request conflicts with the current state of the resource."),
    /**
     * The same {@code Idempotency-Key} was reused with a different request, or a concurrent
     * request holds it. A 409 like {@link #CONFLICT}, and deliberately a separate code and a
     * separate type: this is the one 4xx §7.1 permits an SDK to retry, and a client cannot
     * tell the two apart from the status alone. Introduced in M21.4 alongside the type
     * vocabulary — the condition was always distinguishable in payment-service's
     * idempotency guard, it just had no name in the contract.
     */
    IDEMPOTENCY_CONFLICT(409, ErrorType.IDEMPOTENCY_ERROR,
            "This Idempotency-Key was already used with a different request."),
    /**
     * The per-key token bucket is empty (M20.5). Spelled {@code RATE_LIMIT_EXCEEDED} because
     * that is what the gateway has been sending since M20.5 — the enum previously read
     * {@code RATE_LIMITED} and had **zero** usages anywhere, so the catalogue named a code
     * nothing sent while the wire carried one the catalogue did not list. Renamed to the
     * shipped spelling in M21.4 rather than the other way round: the wire form is the public
     * promise, and no response changes as a result.
     */
    RATE_LIMIT_EXCEEDED(429, ErrorType.RATE_LIMIT_ERROR,
            "Too many requests. Slow down and retry after the interval in the Retry-After header."),
    /**
     * The merchant's daily request quota for this mode is spent (M20.6). Distinct from
     * {@link #RATE_LIMIT_EXCEEDED} and both are 429: one clears in seconds, the other at
     * 00:00 UTC, and an SDK backing off exponentially against the second would retry for
     * hours. Also catalogued in M21.4 from a string literal.
     */
    DAILY_QUOTA_EXCEEDED(429, ErrorType.RATE_LIMIT_ERROR,
            "The daily request quota for this merchant and mode has been exhausted. It resets at 00:00 UTC."),
    INTERNAL_ERROR(500, ErrorType.API_ERROR, "An unexpected error occurred."),
    SERVICE_UNAVAILABLE(503, ErrorType.API_ERROR, "The service is temporarily unavailable.");

    private final int httpStatus;
    private final ErrorType type;
    private final String defaultMessage;

    CommonErrorCode(int httpStatus, ErrorType type, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.type = type;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }

    @Override
    public ErrorType type() {
        return type;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }
}
