package com.paymentflow.common.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InternalContextSignerTest {

    private final InternalContextSigner signer = new InternalContextSigner();
    private static final String SECRET = "test-shared-secret";

    @Test
    void matchesARoundTrippedSignature() {
        String signature = signer.sign(SECRET, "merchant-1", "test", "key-1", "payments:read,payments:write",
                "billing@acme.test", "https://acme.test/hooks", 1_700_000_000L);

        boolean valid = signer.matches(SECRET, "merchant-1", "test", "key-1", "payments:read,payments:write",
                "billing@acme.test", "https://acme.test/hooks", 1_700_000_000L, signature);

        assertThat(valid).isTrue();
    }

    @Test
    void rejectsATamperedField() {
        String signature = signer.sign(SECRET, "merchant-1", "test", "key-1", "payments:read",
                "billing@acme.test", null, 1_700_000_000L);

        // merchantId changed after signing — same signature, different claimed identity.
        boolean valid = signer.matches(SECRET, "merchant-ATTACKER", "test", "key-1", "payments:read",
                "billing@acme.test", null, 1_700_000_000L, signature);

        assertThat(valid).isFalse();
    }

    @Test
    void rejectsAWrongSecret() {
        String signature = signer.sign(SECRET, "merchant-1", "test", "key-1", "payments:read",
                "billing@acme.test", null, 1_700_000_000L);

        boolean valid = signer.matches("a-different-secret", "merchant-1", "test", "key-1", "payments:read",
                "billing@acme.test", null, 1_700_000_000L, signature);

        assertThat(valid).isFalse();
    }

    @Test
    void rejectsAMissingSignature() {
        boolean valid = signer.matches(SECRET, "merchant-1", "test", "key-1", "payments:read",
                "billing@acme.test", null, 1_700_000_000L, null);

        assertThat(valid).isFalse();
    }

    @Test
    void toleratesNullContactEmailAndWebhookUrlOnBothSides() {
        String signature = signer.sign(SECRET, "merchant-1", "live", "key-2", "*", null, null, 42L);

        boolean valid = signer.matches(SECRET, "merchant-1", "live", "key-2", "*", null, null, 42L, signature);

        assertThat(valid).isTrue();
    }
}
