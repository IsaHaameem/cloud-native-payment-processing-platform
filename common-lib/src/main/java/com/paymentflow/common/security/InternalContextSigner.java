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
 */
public final class InternalContextSigner {

    private static final String ALGORITHM = "HmacSHA256";

    /** Computes the hex-encoded HMAC-SHA256 signature over the canonical context string. */
    public String sign(String secret, String merchantId, String mode, String keyId, String scopesCsv,
                       String contactEmail, String webhookUrl, long issuedAtEpochSecond) {
        String canonical = canonical(merchantId, mode, keyId, scopesCsv, contactEmail, webhookUrl, issuedAtEpochSecond);
        return hmacHex(secret, canonical);
    }

    /** Constant-time comparison against a freshly computed signature — never short-circuiting on mismatch. */
    public boolean matches(String secret, String merchantId, String mode, String keyId, String scopesCsv,
                           String contactEmail, String webhookUrl, long issuedAtEpochSecond,
                           String candidateSignatureHex) {
        if (candidateSignatureHex == null) {
            return false;
        }
        String expected = sign(secret, merchantId, mode, keyId, scopesCsv, contactEmail, webhookUrl, issuedAtEpochSecond);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), candidateSignatureHex.getBytes(StandardCharsets.UTF_8));
    }

    private static String canonical(String merchantId, String mode, String keyId, String scopesCsv,
                                    String contactEmail, String webhookUrl, long issuedAtEpochSecond) {
        return merchantId + '|' + mode + '|' + keyId + '|' + scopesCsv + '|'
                + nullToEmpty(contactEmail) + '|' + nullToEmpty(webhookUrl) + '|' + issuedAtEpochSecond;
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
