package com.paymentflow.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generates high-entropy opaque secrets (refresh tokens, API keys, ...) and hashes them
 * for storage. The raw value is only ever handed to the caller once; only the SHA-256
 * hash is persisted, so a database leak cannot be used to replay the secret.
 */
public final class OpaqueTokenGenerator {

    private static final int DEFAULT_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private OpaqueTokenGenerator() {
    }

    /** Generates a 32-byte (256-bit) URL-safe, unpadded Base64 secret. */
    public static String generate() {
        return generate(DEFAULT_BYTES);
    }

    public static String generate(int byteLength) {
        byte[] bytes = new byte[byteLength];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Hex-encoded SHA-256 digest of the given raw value, for storing instead of the value itself. */
    public static String sha256Hex(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
