package dev.paymentflow;

import java.util.Map;

/**
 * The event envelope a verified webhook delivery contains.
 *
 * <p>Hand-written rather than reused from {@link dev.paymentflow.model.EventResponse}, because
 * the two are genuinely different shapes: {@code /v1/events} returns no {@code apiVersion}, a
 * delivery does. {@code id} ({@code evt_} + 32 hex) is stable across retries and replays —
 * <b>dedupe on it</b>. {@code data.object} is the resource the event happened to, as it was at
 * the time; its shape depends on {@code type} and is deliberately open, because a handler
 * branches on {@code type} before reading it.
 */
public record WebhookEvent(
        String id,
        String object,
        String type,
        String apiVersion,
        String created,
        String mode,
        Map<String, Object> data) {

    /** {@code data.object}, or {@code null}. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> dataObject() {
        Object object = data == null ? null : data.get("object");
        return object instanceof Map ? (Map<String, Object>) object : null;
    }
}
