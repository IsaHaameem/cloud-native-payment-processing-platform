package com.paymentflow.sandbox.mapper;

import com.paymentflow.sandbox.domain.TestCard;
import com.paymentflow.sandbox.dto.TestCardResponse;
import org.springframework.stereotype.Component;

@Component
public class TestCardMapper {

    public TestCardResponse toResponse(TestCard card) {
        return new TestCardResponse(
                card.getToken(),
                card.getBrand(),
                card.getOutcome().name(),
                card.getDeclineCode(),
                card.getErrorCode(),
                card.getLatencyMs(),
                card.getCaptureBehaviour().name(),
                card.getRefundBehaviour().name(),
                card.getDeferredDelayMs(),
                card.getDescription());
    }
}
