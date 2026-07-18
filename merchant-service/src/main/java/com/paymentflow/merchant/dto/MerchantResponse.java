package com.paymentflow.merchant.dto;

import java.time.Instant;
import java.util.UUID;

public record MerchantResponse(
        UUID id,
        String businessName,
        String contactEmail,
        Instant createdAt,
        Instant updatedAt) {
}
