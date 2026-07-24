package com.paymentflow.sandbox.mapper;

import com.paymentflow.sandbox.domain.DecisionLogEntry;
import com.paymentflow.sandbox.domain.Operation;
import com.paymentflow.sandbox.dto.DecisionLogEntryResponse;
import org.springframework.stereotype.Component;

@Component
public class DecisionLogMapper {

    public DecisionLogEntryResponse toResponse(DecisionLogEntry entry) {
        Operation deferredOperation = entry.getDeferredOperation();
        return new DecisionLogEntryResponse(
                entry.getDecisionKey(),
                entry.getPaymentId(),
                entry.getOperation().name(),
                entry.getOutcome().name(),
                entry.getDeclineCode(),
                entry.getErrorCode(),
                entry.getLatencyMs(),
                entry.getSource().name(),
                entry.getOverrideId(),
                deferredOperation == null ? null : deferredOperation.name(),
                entry.getDeferredDelayMs(),
                entry.getCreatedAt());
    }
}
