package com.paymentflow.identity.dto;

/**
 * Issued token pair.
 *
 * @param accessToken  short-lived signed JWT (Bearer)
 * @param refreshToken long-lived opaque token used to obtain new access tokens
 * @param tokenType    always {@code Bearer}
 * @param expiresIn    access-token lifetime in seconds
 */
public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn) {

    public static AuthResponse bearer(String accessToken, String refreshToken, long expiresIn) {
        return new AuthResponse(accessToken, refreshToken, "Bearer", expiresIn);
    }
}
