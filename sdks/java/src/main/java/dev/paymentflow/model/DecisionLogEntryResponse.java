package dev.paymentflow.model;

/**
 * One sandbox decision: what the simulated acquirer decided about one operation on one payment,
 * and — in {@code source} — <b>why</b> it decided that (the test card's catalogue entry, an
 * active simulation override, or the mode's default). Test mode only.
 */
public record DecisionLogEntryResponse(
        String createdAt,
        String decisionKey,
        String declineCode,
        Long deferredDelayMs,
        String deferredOperation,
        String errorCode,
        Long latencyMs,
        String operation,
        String outcome,
        String overrideId,
        String paymentId,
        String source) {}
