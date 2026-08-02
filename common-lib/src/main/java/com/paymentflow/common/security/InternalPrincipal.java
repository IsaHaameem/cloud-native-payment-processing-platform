package com.paymentflow.common.security;

import java.util.Locale;

/**
 * What kind of credential the gateway authenticated before asserting a merchant context
 * (M23.0, D185).
 *
 * <p>Until M23 there was only one answer, so it was never written down: every signed
 * context came from an API key, and {@code keyId} identified the caller completely. The
 * developer portal introduces a second answer — a dashboard session, which has a user but
 * no key — and the two must be distinguishable downstream. A refund initiated by a human
 * in the portal and one initiated by a merchant's server are otherwise indistinguishable
 * in every service that records them.
 *
 * <p>Carried in the signed payload rather than inferred from which identity fields happen
 * to be present: inference would make "no key id" mean "session" forever, which stops
 * being true the first time any other credential is added.
 */
public enum InternalPrincipal {

    /** An API key (`sk_`/`pk_`) verified against merchant-service. {@code keyId} identifies it. */
    API_KEY("api_key"),

    /** A dashboard session (an identity-service JWT). {@code userId} identifies it; there is no key. */
    SESSION("session");

    private final String wireValue;

    InternalPrincipal(String wireValue) {
        this.wireValue = wireValue;
    }

    /** The lowercase spelling carried on the wire and included in the signature. */
    public String wireValue() {
        return wireValue;
    }

    /**
     * Parses the header form. An absent value means {@link #API_KEY} — every context signed
     * before M23.0, and every service-to-service caller that still uses the API-key form of
     * {@link InternalContextSigner#sign}, omits the header entirely. Unrecognised values are
     * rejected rather than defaulted, so a future principal cannot silently be treated as a key.
     */
    public static InternalPrincipal fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            return API_KEY;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (InternalPrincipal principal : values()) {
            if (principal.wireValue.equals(normalized)) {
                return principal;
            }
        }
        return null;
    }
}
