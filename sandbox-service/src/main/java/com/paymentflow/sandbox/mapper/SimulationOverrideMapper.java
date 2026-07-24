package com.paymentflow.sandbox.mapper;

import com.paymentflow.sandbox.domain.SimulationOverride;
import com.paymentflow.sandbox.dto.SimulationOverrideResponse;
import org.springframework.stereotype.Component;

@Component
public class SimulationOverrideMapper {

    private static final String WEBHOOK_ENACTED_FROM = "M18";

    public SimulationOverrideResponse toResponse(SimulationOverride override) {
        return new SimulationOverrideResponse(
                override.getId(),
                override.getScenario().name(),
                override.getDeclineCode(),
                override.getErrorCode(),
                override.getLatencyMs(),
                override.getRemainingCount(),
                override.getExpiresAt(),
                override.getScenario().isWebhookScenario() ? WEBHOOK_ENACTED_FROM : null);
    }
}
