package com.paymentflow.payment.dto;

import com.paymentflow.common.openapi.PublicApiParameters;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * {@code paymentMethodToken} (M17, D130) is optional — a payment created without one
 * behaves exactly as every payment did before M17 (mode-default authorization,
 * M17.4). No validation beyond a length bound: the token's meaning (a known test card,
 * or unrecognised) is sandbox-service's business, not payment-service's.
 *
 * <p>{@code metadata} (M19.2) is optional free-form key/value data, never interpreted by
 * this platform and filterable via containment on the public list. Also additive: a
 * pre-M19 caller sends none and gets an empty object back.
 */
public record CreatePaymentRequest(
        @Schema(description = """
                The amount to charge, as an integer in the currency's **minor unit**: \
                `1000` in `USD` is $10.00. There are no floating-point amounts anywhere in \
                this API. Must be positive.""",
                example = "1000")
        @Positive long amountMinor,

        @Schema(description = "The three-letter ISO 4217 currency code, such as `USD`.",
                example = "USD")
        @NotBlank @Size(min = 3, max = 3) String currency,

        @Schema(description = """
                An arbitrary description of what is being paid for, up to 500 characters. \
                For your own records — PaymentFlow never shows it to your customer.""",
                example = "Order A-1234")
        @Size(max = 500) String description,

        @Schema(description = """
                A payment-method token to authorize against. Optional: a payment created \
                without one takes the mode's default authorization behaviour. In test mode, \
                pass one of the tokens from `GET /v1/test/cards` to choose the outcome.""",
                example = "tok_visa_approved")
        @Size(max = 64) String paymentMethodToken,

        @Schema(description = PublicApiParameters.METADATA_FIELD)
        Map<String, String> metadata) {
}
