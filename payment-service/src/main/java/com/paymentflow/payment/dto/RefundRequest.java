package com.paymentflow.payment.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * {@code amountMinor} is optional — omitted (or a null body) means "refund whatever
 * remains captured".
 *
 * <p>M19.3 adds {@code reason} and {@code metadata}, both optional and both additive:
 * every pre-M19 caller sends neither and behaves exactly as before. They exist because a
 * refund is now an object a merchant will read back, and an object with no explanation
 * of why it happened is markedly less useful than one with it.
 */
public record RefundRequest(
        @Positive Long amountMinor,
        @Size(max = 500) String reason,
        Map<String, String> metadata) {
}
