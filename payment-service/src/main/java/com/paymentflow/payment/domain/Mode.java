package com.paymentflow.payment.domain;

import java.util.Locale;

/**
 * The test/live data-plane partition a payment (and its idempotency record and events)
 * belongs to (M16, §4.4). The canonical persisted and on-the-wire form is the lowercase
 * value ({@code "test"}/{@code "live"}) — matching the API-key mode segment, the
 * gateway-signed {@code X-PF-Internal-Mode}, and {@code EventEnvelope.mode} — not the enum
 * constant name.
 *
 * <p>Local to payment-service (schema-per-service): merchant-service's {@code KeyMode} is
 * the same idea for a different domain, never shared across the service boundary. Used only
 * at the request boundary ({@link com.paymentflow.payment.mode.RequestModeResolver}) to
 * validate and normalise an incoming mode; entities and events store the {@link #value()}
 * string directly, so nothing else needs to know this enum exists.
 */
public enum Mode {

    TEST("test"),
    LIVE("live");

    private final String value;

    Mode(String value) {
        this.value = value;
    }

    /** The canonical lowercase form, e.g. {@code "test"}. */
    public String value() {
        return value;
    }

    /** Parses the canonical form case-insensitively; rejects anything that is not a known mode. */
    public static Mode parse(String raw) {
        if (raw != null) {
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            for (Mode mode : values()) {
                if (mode.value.equals(normalized)) {
                    return mode;
                }
            }
        }
        throw new IllegalArgumentException("Unknown mode: " + raw);
    }
}
