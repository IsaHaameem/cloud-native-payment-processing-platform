package com.paymentflow.common.dto.error;

/**
 * The coarse classification every error response carries as {@code type} (M21.4, §5/M21
 * task 3).
 *
 * <p><b>Why a type as well as a code.</b> {@link ApiError#code()} is specific and there are
 * many of them; the set grows every time the platform learns to fail in a new way, and
 * §4.10 says adding one is an additive change that ships unversioned. An integrator cannot
 * write a {@code switch} over a set that is allowed to grow. {@code type} is the small,
 * stable half of that contract: it answers "what kind of problem is this, and is retrying
 * plausible?" without naming the specific cause, and it is what §7.1's SDKs map onto their
 * typed exception hierarchy.
 *
 * <p>The wire form is {@code snake_case} rather than this enum's name. That is deliberate
 * and it is the one place this platform's camelCase convention does not apply: these are
 * enum <em>values</em>, not field names, and every comparable payments API spells them this
 * way — an SDK author porting a type map from elsewhere should not have to translate.
 *
 * <p>There is deliberately no {@code api_connection_error}. §7.1 lists one in the SDK
 * hierarchy, but it describes a request that never reached the platform — a DNS failure, a
 * dropped socket — so there is no response for it to appear in. The SDKs raise it
 * themselves; the server can never send it.
 */
public enum ErrorType {

    /** The credential is missing, malformed, revoked, or not a key at all. 401. */
    AUTHENTICATION_ERROR("authentication_error"),

    /**
     * The credential is valid but is not allowed to do this — a missing scope, or the wrong
     * mode. Distinct from {@link #AUTHENTICATION_ERROR} because the remedy is different:
     * one means "fix your key", the other means "use a key with more access".
     */
    PERMISSION_ERROR("permission_error"),

    /**
     * The request itself is wrong: a malformed body, a failed validation, a resource that
     * does not exist, or a state transition the resource does not allow. Never retryable
     * unchanged.
     */
    INVALID_REQUEST_ERROR("invalid_request_error"),

    /**
     * An {@code Idempotency-Key} was reused with a different request, or a concurrent
     * request holds the same key. Separated from {@link #INVALID_REQUEST_ERROR} because it
     * is the one 4xx an SDK may legitimately retry — the concurrent case resolves itself —
     * and §7.1 gives it its own exception class for exactly that reason.
     */
    IDEMPOTENCY_ERROR("idempotency_error"),

    /** The key's request allowance is exhausted. Retryable, and the headers say when. 429. */
    RATE_LIMIT_ERROR("rate_limit_error"),

    /**
     * Something failed on PaymentFlow's side. The only type for which the caller did
     * nothing wrong, and the only 5xx type — which is what makes "retry on 5xx" (§7.1) a
     * rule an SDK can implement from the type alone.
     */
    API_ERROR("api_error");

    private final String wireName;

    ErrorType(String wireName) {
        this.wireName = wireName;
    }

    /** The value that appears in the JSON body. */
    public String wireName() {
        return wireName;
    }
}
