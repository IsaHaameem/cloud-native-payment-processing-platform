package com.paymentflow.sandbox.domain;

/**
 * Why a decision came out the way it did (§4.2/§7) — the field a live decision must
 * never carry {@code OVERRIDE} or {@code TEST_CARD} for, since live mode never reads
 * developer-controllable state (§7's structural mode-isolation guarantee). {@code
 * ACQUIRER} is wired in M17.7; until then, live decisions use {@code MODE_DEFAULT} like
 * test decisions do, and only the eventual acquirer distribution differs.
 */
public enum DecisionSource {
    OVERRIDE,
    TEST_CARD,
    MODE_DEFAULT,
    ACQUIRER
}
