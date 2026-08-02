package com.paymentflow.common.security;

/**
 * Header names carrying a gateway-asserted, HMAC-signed merchant context between
 * services (M15). Every header in this family is stripped unconditionally by the
 * gateway's {@code InternalHeaderStrippingWebFilter} on ingress — a client can never
 * set one of these itself — and the signature is the second, independent line of
 * defense in case that stripping is ever bypassed.
 */
public final class InternalContextHeaders {

    /** Prefix shared by every header in this family, used by the gateway to strip inbound copies. */
    public static final String HEADER_PREFIX = "X-PF-Internal-";

    public static final String MERCHANT_ID = "X-PF-Internal-Merchant-Id";
    public static final String MODE = "X-PF-Internal-Mode";
    public static final String KEY_ID = "X-PF-Internal-Key-Id";
    public static final String SCOPES = "X-PF-Internal-Scopes";

    /**
     * Which credential the gateway authenticated (M23.0, D185) — {@code api_key} or
     * {@code session}. Absent means {@code api_key}: every caller that predates the
     * developer portal omits it, and {@link InternalPrincipal#fromWireValue} defaults
     * accordingly, so no existing signer had to change.
     */
    public static final String PRINCIPAL = "X-PF-Internal-Principal";

    /**
     * The identity-service user behind a dashboard session (M23.0, D185). Present only
     * when {@link #PRINCIPAL} is {@code session}; a key has no user. This is the field
     * that makes a portal-initiated mutation attributable to a person.
     */
    public static final String USER_ID = "X-PF-Internal-User-Id";
    public static final String CONTACT_EMAIL = "X-PF-Internal-Contact-Email";
    public static final String WEBHOOK_URL = "X-PF-Internal-Webhook-Url";
    public static final String ISSUED_AT = "X-PF-Internal-Issued-At";
    public static final String SIGNATURE = "X-PF-Internal-Signature";

    /** Comma-separated join/split character for the scopes header. */
    public static final String SCOPES_DELIMITER = ",";

    private InternalContextHeaders() {
    }
}
