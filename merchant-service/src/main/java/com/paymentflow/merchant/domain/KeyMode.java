package com.paymentflow.merchant.domain;

/** Test and live are disjoint from the key outward — M16 extends this to every row a key can reach. */
public enum KeyMode {

    TEST("test"),
    LIVE("live");

    private final String value;

    KeyMode(String value) {
        this.value = value;
    }

    /** The raw-key mode segment, e.g. {@code "test"} in {@code pk_test_...}. */
    public String value() {
        return value;
    }

    public static KeyMode fromValue(String value) {
        for (KeyMode mode : values()) {
            if (mode.value.equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown API key mode: " + value);
    }
}
