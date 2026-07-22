package com.paymentflow.payment.mapper;

import com.paymentflow.payment.domain.Payment;
import com.paymentflow.payment.dto.PaymentResponse;
import org.springframework.stereotype.Component;

@Component
public class PaymentMapper {

    public PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getMerchantId(),
                payment.getMode(),
                payment.getAmountMinor(),
                payment.getCurrency(),
                payment.getStatus().name(),
                payment.getCapturedAmountMinor(),
                payment.getRefundedAmountMinor(),
                payment.getDescription(),
                payment.getFailureReason(),
                payment.getCreatedAt(),
                payment.getUpdatedAt());
    }
}
