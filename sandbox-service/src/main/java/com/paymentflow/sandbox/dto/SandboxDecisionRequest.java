package com.paymentflow.sandbox.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * The internal decision request (§3.2). Deliberately carries no {@code merchantId}/
 * {@code mode} — those come from the verified signed internal context
 * ({@code MerchantContextHolder}), never from a client-suppliable field, so a caller
 * cannot assert a decision for a merchant/mode other than the one it was authenticated
 * as (§7's isolation barrier ①).
 */
public record SandboxDecisionRequest(
        @NotBlank String decisionKey,
        @NotNull UUID paymentId,
        @NotBlank String operation,
        String paymentMethodToken,
        @Positive long amountMinor,
        @NotBlank @Size(min = 3, max = 3) String currency) {
}
