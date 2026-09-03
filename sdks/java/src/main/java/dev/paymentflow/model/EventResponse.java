package dev.paymentflow.model;

import java.util.Map;

/**
 * One event from your event log. {@code id} ({@code evt_} + 32 hex) is byte-identical to the
 * {@code id} in the webhook body you received for the same event, so a webhook reconciles
 * against this log with nothing extra stored. {@code data} is the payload, kept verbatim and
 * deliberately open — branch on {@code type} before reading it. New {@code type} values ship
 * without a new API revision.
 */
public record EventResponse(
        String created,
        Map<String, Object> data,
        String id,
        String mode,
        String object,
        String type) {}
