package com.paymentflow.payment.dto;

import com.paymentflow.common.exception.BadRequestException;
import com.paymentflow.payment.domain.RefundStatus;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * The refund-specific half of a list request (M19.3), mirroring
 * {@link PaymentListFilter}. Validated at construction for the same reason: a typo'd
 * status silently returning zero rows is worse than a 400 that names the mistake.
 */
public record RefundListFilter(UUID paymentId, String status, String metadataJson) {

    public static RefundListFilter of(UUID paymentId, String status, Map<String, String> metadata,
                                      Function<Map<String, String>, String> toJson) {
        return new RefundListFilter(paymentId, normalizeStatus(status),
                (metadata == null || metadata.isEmpty()) ? null : toJson.apply(metadata));
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return RefundStatus.valueOf(status.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown refund status: " + status + ".");
        }
    }
}
