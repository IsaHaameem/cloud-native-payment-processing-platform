package com.paymentflow.payment.dto;

import com.paymentflow.common.openapi.PublicApiParameters;
import io.swagger.v3.oas.annotations.media.Schema;
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
        @Schema(description = """
                How much to refund, in the currency's minor unit. Omit it — or send no body \
                at all — to refund everything that remains captured.""",
                example = "1000")
        @Positive Long amountMinor,

        @Schema(description = """
                Why the refund was issued, up to 500 characters. For your own records; \
                PaymentFlow never interprets it.""",
                example = "Customer returned the item")
        @Size(max = 500) String reason,

        @Schema(description = PublicApiParameters.METADATA_FIELD)
        Map<String, String> metadata) {
}
