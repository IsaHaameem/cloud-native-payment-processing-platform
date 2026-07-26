package com.paymentflow.notification.service;

import java.security.SecureRandom;

/**
 * Generates a webhook signing secret, {@code whsec_<base62>} (§4.5). Mirrors
 * merchant-service's {@code ApiKeySecretGenerator} deliberately — same alphabet, same
 * shape, same reason for not using common-lib's {@code OpaqueTokenGenerator} to produce
 * it: that helper emits Base64URL, and a secret a developer will copy out of a dashboard
 * and paste into an environment variable is better off without {@code -} and {@code _}
 * in it. {@code OpaqueTokenGenerator.sha256Hex} is still what hashes the result for
 * storage — only the character set differs, not the handling.
 *
 * <p>32 characters of base62 is ~190 bits of entropy, comfortably above the 128-bit
 * floor for an HMAC key and longer than the 24 characters an API key uses, because a
 * signing secret is never rotated on a schedule the way a key can be.
 */
final class WebhookSecretGenerator {

    /** The public prefix, kept in the clear so a merchant can identify a secret without revealing it. */
    static final String PREFIX = "whsec_";

    /** How much of the raw secret is retained in the clear alongside its hash. */
    static final int STORED_PREFIX_LENGTH = 12;

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int LENGTH = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private WebhookSecretGenerator() {
    }

    /** The full raw secret, e.g. {@code whsec_9fK2…}. Handed to the caller exactly once; never stored. */
    static String generate() {
        StringBuilder builder = new StringBuilder(PREFIX.length() + LENGTH).append(PREFIX);
        for (int i = 0; i < LENGTH; i++) {
            builder.append(ALPHABET.charAt(SECURE_RANDOM.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }

    /** The displayable fragment of a raw secret — enough to tell two apart, far too little to forge one. */
    static String storedPrefixOf(String rawSecret) {
        return rawSecret.substring(0, Math.min(STORED_PREFIX_LENGTH, rawSecret.length()));
    }
}
