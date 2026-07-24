package com.paymentflow.payment.authorization;

/**
 * The provider-neutral verdict on one {@link AuthorizationRequest} (D132).
 * {@code declineCode}/{@code errorCode} are populated only for the matching
 * {@link AuthorizationOutcome}; both are {@code null} for {@code APPROVED} and
 * {@code PENDING}.
 */
public record AuthorizationDecision(AuthorizationOutcome outcome, String declineCode, String errorCode) {

    public static AuthorizationDecision approved() {
        return new AuthorizationDecision(AuthorizationOutcome.APPROVED, null, null);
    }

    public static AuthorizationDecision declined(String declineCode) {
        return new AuthorizationDecision(AuthorizationOutcome.DECLINED, declineCode, null);
    }

    public static AuthorizationDecision error(String errorCode) {
        return new AuthorizationDecision(AuthorizationOutcome.ERROR, null, errorCode);
    }
}
