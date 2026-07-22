package com.paymentflow.merchant.domain;

/**
 * Publishable keys are read-only and safe for a browser; secret keys are full-access
 * and must never reach one (M15, §4.3).
 */
public enum ApiKeyType {

    PUBLISHABLE("pk"),
    SECRET("sk");

    private final String prefix;

    ApiKeyType(String prefix) {
        this.prefix = prefix;
    }

    /** The raw-key prefix segment, e.g. {@code "pk"} in {@code pk_test_...}. */
    public String prefix() {
        return prefix;
    }

    public static ApiKeyType fromPrefix(String prefix) {
        for (ApiKeyType type : values()) {
            if (type.prefix.equals(prefix)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown API key type prefix: " + prefix);
    }
}
