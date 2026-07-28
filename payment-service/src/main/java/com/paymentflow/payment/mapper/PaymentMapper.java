package com.paymentflow.payment.mapper;

import com.paymentflow.payment.domain.Payment;
import com.paymentflow.payment.domain.Refund;
import com.paymentflow.payment.dto.PaymentResponse;
import com.paymentflow.payment.dto.RefundResponse;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Entity → response translation for both payments and refunds.
 *
 * <p>Metadata is stored as a {@code jsonb} string and exposed as a {@code Map} — parsed
 * here rather than anywhere else, so "what shape does metadata have on the wire" has one
 * answer. Values are strings only, matching the platform's documented contract: a
 * merchant nesting structured data inside a metadata *value* is storing something this
 * platform will never index or filter on, and a richer type would suggest otherwise.
 */
@Component
public class PaymentMapper {

    private static final TypeReference<Map<String, String>> METADATA_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public PaymentMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                PaymentResponse.OBJECT_TYPE,
                payment.getMerchantId(),
                payment.getMode(),
                payment.getAmountMinor(),
                payment.getCurrency(),
                payment.getStatus().wireName(),
                payment.getCapturedAmountMinor(),
                payment.getRefundedAmountMinor(),
                payment.getDescription(),
                payment.getPaymentMethodToken(),
                payment.getFailureReason(),
                readMetadata(payment.getMetadata()),
                // Null, not empty: an absent `refunds` means "you did not ask to expand
                // it", which is a different statement from "this payment has none".
                null,
                payment.getCreatedAt(),
                payment.getUpdatedAt());
    }

    public RefundResponse toResponse(Refund refund) {
        return new RefundResponse(
                refund.getId(),
                RefundResponse.OBJECT_TYPE,
                refund.getPaymentId(),
                refund.getMerchantId(),
                refund.getMode(),
                refund.getAmountMinor(),
                refund.getCurrency(),
                refund.getStatus().wireName(),
                refund.getReason(),
                refund.getFailureReason(),
                readMetadata(refund.getMetadata()),
                refund.getCreatedAt(),
                refund.getUpdatedAt());
    }

    /** Serializes merchant-supplied metadata for storage; {@code null} means "not supplied". */
    public String writeMetadata(Map<String, String> metadata) {
        return (metadata == null || metadata.isEmpty()) ? null : objectMapper.writeValueAsString(metadata);
    }

    private Map<String, String> readMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(json, METADATA_TYPE);
    }
}
