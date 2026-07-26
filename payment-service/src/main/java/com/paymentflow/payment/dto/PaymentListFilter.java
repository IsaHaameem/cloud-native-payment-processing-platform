package com.paymentflow.payment.dto;

import com.paymentflow.common.exception.BadRequestException;
import com.paymentflow.payment.domain.PaymentStatus;

import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The payment-specific half of a list request (M19.2). The generic half — limit, cursor,
 * created range — lives in {@code common-lib}'s {@code ListQuery}, because those are the
 * same for every resource and a per-resource copy is how five endpoints drift apart.
 *
 * <p>Validated at construction so an unusable filter fails as a 400 at the edge rather
 * than as an empty result set the merchant then has to explain. A typo'd status is the
 * likeliest case by far, and returning zero payments for {@code status=AUTHORISED} would
 * be actively misleading.
 */
public record PaymentListFilter(String status, String currency, Long amountMin, Long amountMax,
                                String metadataJson) {

    public static PaymentListFilter of(String status, String currency, Long amountMin, Long amountMax,
                                       Map<String, String> metadata, Function<Map<String, String>, String> toJson) {
        String normalizedStatus = normalizeStatus(status);
        String normalizedCurrency = normalizeCurrency(currency);
        validateAmountRange(amountMin, amountMax);
        // Null, not "{}": an empty containment filter matches every row, so the absence of
        // a metadata filter must be a null bind rather than an empty object.
        String metadataJson = (metadata == null || metadata.isEmpty()) ? null : toJson.apply(metadata);
        return new PaymentListFilter(normalizedStatus, normalizedCurrency, amountMin, amountMax, metadataJson);
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String upper = status.trim().toUpperCase(Locale.ROOT);
        try {
            return PaymentStatus.valueOf(upper).name();
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown status: " + status + ".");
        }
    }

    private static String normalizeCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return null;
        }
        String upper = currency.trim().toUpperCase(Locale.ROOT);
        if (upper.length() != 3) {
            throw new BadRequestException("currency must be a 3-letter code.");
        }
        return upper;
    }

    private static void validateAmountRange(Long amountMin, Long amountMax) {
        if (amountMin != null && amountMin < 0) {
            throw new BadRequestException("amount_min must not be negative.");
        }
        if (amountMin != null && amountMax != null && amountMin > amountMax) {
            throw new BadRequestException("amount_min must not exceed amount_max.");
        }
    }
}
