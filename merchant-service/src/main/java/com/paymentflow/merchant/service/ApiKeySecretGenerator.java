package com.paymentflow.merchant.service;

import java.security.SecureRandom;

/**
 * Generates the base62 secret segment of a raw API key ({@code {pk|sk}_{test|live}_<this>},
 * §4.3). Deliberately separate from common-lib's {@code OpaqueTokenGenerator} (Base64URL
 * output): the key format calls for base62 specifically, and refresh tokens/the V1 key
 * format both keep their existing Base64URL shape unaffected.
 */
final class ApiKeySecretGenerator {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int LENGTH = 24;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private ApiKeySecretGenerator() {
    }

    static String generate() {
        StringBuilder builder = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            builder.append(ALPHABET.charAt(SECURE_RANDOM.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }
}
