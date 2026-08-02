package com.paymentflow.common.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Signs and verifies the gateway-asserted merchant context carried in
 * {@link InternalContextHeaders} (M15, D100). One shared secret, sourced from
 * Secrets Manager in AWS and {@code .env} locally (mirrors D18/D73's existing
 * env-var-now pattern) — the gateway signs, every downstream service's
 * {@code InternalContextFilter} verifies, both via this same class so the canonical
 * string can never drift between producer and verifier.
 *
 * <p>Every trusted field the gateway resolved (not just the merchant/mode/key/scope
 * identity, but also the contact email and webhook URL a consumer needs — see D118)
 * is included in the signed payload, so a partial tamper of any single header is
 * caught, not only tampering with the identity fields.
 *
 * <p>M23.0 adds {@code principal} and {@code userId} (D185). Both are signed, which is the
 * point: without them in the payload, a dashboard session's context could be relabelled as
 * an API key's — or the user attributed to it swapped — by editing a header the signature
 * did not cover. Each method has an API-key-shaped overload that omits them, because that is
 * what every caller written before the portal genuinely is.
 */
public final class InternalContextSigner {

    private static final String ALGORITHM = "HmacSHA256";

    /**
     * The API-key form (M15): principal {@link InternalPrincipal#API_KEY}, no user.
     *
     * <p>Retained as the shape every pre-M23 caller already uses — the gateway's API-key
     * filter, payment-service's sandbox advisor, notification-service's scenario client and
     * every test that signs a context by hand. They are all genuinely API-key callers, so
     * this is the correct call for them rather than a compatibility shim.
     */
    public String sign(String secret, String merchantId, String mode, String keyId, String scopesCsv,
                       String contactEmail, String webhookUrl, long issuedAtEpochSecond) {
        return sign(secret, merchantId, mode, InternalPrincipal.API_KEY, null, keyId, scopesCsv,
                contactEmail, webhookUrl, issuedAtEpochSecond);
    }

    /**
     * Computes the hex-encoded HMAC-SHA256 signature over the canonical context string
     * (M23.0: {@code principal} and {@code userId} added, D185).
     *
     * <p>{@code keyId} is null for a session and {@code userId} is null for an API key —
     * both are part of the signed payload either way, so a context cannot be re-labelled as
     * the other kind without invalidating its signature.
     */
    public String sign(String secret, String merchantId, String mode, InternalPrincipal principal, String userId,
                       String keyId, String scopesCsv, String contactEmail, String webhookUrl,
                       long issuedAtEpochSecond) {
        String canonical = canonical(merchantId, mode, principal, userId, keyId, scopesCsv,
                contactEmail, webhookUrl, issuedAtEpochSecond);
        return hmacHex(secret, canonical);
    }

    /** Constant-time comparison against a freshly computed signature — never short-circuiting on mismatch. */
    public boolean matches(String secret, String merchantId, String mode, String keyId, String scopesCsv,
                           String contactEmail, String webhookUrl, long issuedAtEpochSecond,
                           String candidateSignatureHex) {
        return matches(secret, merchantId, mode, InternalPrincipal.API_KEY, null, keyId, scopesCsv,
                contactEmail, webhookUrl, issuedAtEpochSecond, candidateSignatureHex);
    }

    /** Constant-time comparison against a freshly computed signature — never short-circuiting on mismatch. */
    public boolean matches(String secret, String merchantId, String mode, InternalPrincipal principal, String userId,
                           String keyId, String scopesCsv, String contactEmail, String webhookUrl,
                           long issuedAtEpochSecond, String candidateSignatureHex) {
        if (candidateSignatureHex == null) {
            return false;
        }
        String expected = sign(secret, merchantId, mode, principal, userId, keyId, scopesCsv,
                contactEmail, webhookUrl, issuedAtEpochSecond);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), candidateSignatureHex.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The two M23.0 fields are <b>appended</b> rather than inserted, so the change is
     * confined to the tail of the string. Where they sit does not affect security — the whole
     * string is signed — but it keeps the field order the same one a reader of M15's code
     * already knows, and makes the diff between the two forms visible at a glance.
     */
    private static String canonical(String merchantId, String mode, InternalPrincipal principal, String userId,
                                    String keyId, String scopesCsv, String contactEmail, String webhookUrl,
                                    long issuedAtEpochSecond) {
        return merchantId + '|' + mode + '|' + nullToEmpty(keyId) + '|' + scopesCsv + '|'
                + nullToEmpty(contactEmail) + '|' + nullToEmpty(webhookUrl) + '|' + issuedAtEpochSecond
                + '|' + principal.wireValue() + '|' + nullToEmpty(userId);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String hmacHex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
