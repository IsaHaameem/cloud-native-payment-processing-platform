package com.paymentflow.payment.dto;

import jakarta.validation.constraints.Positive;

/** {@code amountMinor} is optional — omitted (or a null body) means "refund whatever remains captured". */
public record RefundRequest(@Positive Long amountMinor) {
}
