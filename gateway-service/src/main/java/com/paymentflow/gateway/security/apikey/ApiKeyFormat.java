package com.paymentflow.gateway.security.apikey;

import com.paymentflow.common.security.OpaqueTokenGenerator;

/**
 * Pure-function credential classification (M15, §4.3 step 2) — deliberately free of
 * any I/O so it stays trivially, exhaustively unit-testable on its own, per the
 * milestone's own risk mitigation ("credential detection is a pure function with its
 * own unit tests").
 */
public final class ApiKeyFormat {

    private ApiKeyFormat() {
    }

    public enum CredentialType {
        JWT, API_KEY, UNKNOWN
    }

    public static CredentialType classify(String credential) {
        if (credential == null || credential.isBlank()) {
            return CredentialType.UNKNOWN;
        }
        if (credential.startsWith("pk_") || credential.startsWith("sk_")) {
            return CredentialType.API_KEY;
        }
        if (dotCount(credential) == 2) {
            return CredentialType.JWT;
        }
        return CredentialType.UNKNOWN;
    }

    public static boolean isPublishable(String rawKey) {
        return rawKey != null && rawKey.startsWith("pk_");
    }

    public static String sha256Hex(String rawKey) {
        return OpaqueTokenGenerator.sha256Hex(rawKey);
    }

    private static int dotCount(String value) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '.') {
                count++;
            }
        }
        return count;
    }
}
