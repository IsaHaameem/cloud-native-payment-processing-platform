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

    // ---------------------------------------------------------------------------------
    // M23.0 — the principal and the user id are part of the signed payload (D185).
    // ---------------------------------------------------------------------------------

    @Test
    void theApiKeyOverloadSignsExactlyWhatAnExplicitApiKeyPrincipalDoes() {
        String viaOverload = signer.sign(SECRET, "merchant-1", "test", "key-1", "payments:read",
                "billing@acme.test", null, 1_700_000_000L);

        String explicit = signer.sign(SECRET, "merchant-1", "test", InternalPrincipal.API_KEY, null, "key-1",
                "payments:read", "billing@acme.test", null, 1_700_000_000L);

        // Not a convenience assertion: every caller written before the developer portal uses
        // the short form, and this is what makes "they are all API-key callers" true rather
        // than merely intended.
        assertThat(viaOverload).isEqualTo(explicit);
    }

    @Test
    void matchesARoundTrippedSessionSignature() {
        String signature = signer.sign(SECRET, "merchant-1", "live", InternalPrincipal.SESSION, "user-9", null,
                "payments:read,payments:write", "billing@acme.test", null, 1_700_000_000L);

        boolean valid = signer.matches(SECRET, "merchant-1", "live", InternalPrincipal.SESSION, "user-9", null,
                "payments:read,payments:write", "billing@acme.test", null, 1_700_000_000L, signature);

        assertThat(valid).isTrue();
    }

    @Test
    void aSessionContextCannotBeRelabelledAsAnApiKeyOne() {
        String signature = signer.sign(SECRET, "merchant-1", "live", InternalPrincipal.SESSION, "user-9", null,
                "payments:write", null, null, 1_700_000_000L);

        // Same fields, same signature, one word changed — the whole reason `principal` is
        // signed rather than inferred from which identity field happens to be present.
        boolean valid = signer.matches(SECRET, "merchant-1", "live", InternalPrincipal.API_KEY, null, null,
                "payments:write", null, null, 1_700_000_000L, signature);

        assertThat(valid).isFalse();
    }

    @Test
    void aSessionSignatureDoesNotSurviveSwappingTheUser() {
        String signature = signer.sign(SECRET, "merchant-1", "live", InternalPrincipal.SESSION, "user-9", null,
                "payments:write", null, null, 1_700_000_000L);

        boolean valid = signer.matches(SECRET, "merchant-1", "live", InternalPrincipal.SESSION, "user-ATTACKER",
                null, "payments:write", null, null, 1_700_000_000L, signature);

        assertThat(valid).isFalse();
    }

    @Test
    void twoPrincipalsOverTheSameFieldsProduceDifferentSignatures() {
        String asKey = signer.sign(SECRET, "merchant-1", "test", InternalPrincipal.API_KEY, null, "key-1",
                "payments:read", null, null, 42L);
        String asSession = signer.sign(SECRET, "merchant-1", "test", InternalPrincipal.SESSION, "key-1", null,
                "payments:read", null, null, 42L);

        assertThat(asKey).isNotEqualTo(asSession);
    }
}
