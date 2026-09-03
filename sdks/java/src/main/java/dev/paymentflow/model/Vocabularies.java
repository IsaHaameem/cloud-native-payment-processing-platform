package dev.paymentflow.model;

import java.util.List;

/**
 * The open vocabularies the contract documents.
 *
 * <p>Every one of these is a field typed as {@code String}, not an {@code enum}. §9 requires a
 * client to tolerate an enum value it has never heard of — that is what makes "a new status
 * value is additive, not breaking" true rather than aspirational — and a Java {@code enum} whose
 * {@code valueOf} threw on an unrecognised value would turn the platform's safest change into an
 * exception in every integrator's code at once.
 *
 * <p>So the documented values live here as {@code List<String>} constants: a set to recognise a
 * value against when you want to know whether you handle it, never a set to validate against.
 * The names and contents are asserted against {@code ../shared/fixtures/enums.json} by
 * {@code ContractParityTest}.
 */
public final class Vocabularies {

    private Vocabularies() {}

    /** {@code ApiError.type} — the closed set this SDK maps to exception classes. */
    public static final List<String> API_ERROR_TYPE_VALUES = List.of(
            "authentication_error", "permission_error", "invalid_request_error",
            "idempotency_error", "rate_limit_error", "api_error");

    /** {@code BalanceTransactionResponse.direction}. */
    public static final List<String> BALANCE_TRANSACTION_RESPONSE_DIRECTION_VALUES = List.of("DEBIT", "CREDIT");

    /** {@code BalanceTransactionResponse.mode}. */
    public static final List<String> BALANCE_TRANSACTION_RESPONSE_MODE_VALUES = List.of("test", "live");

    /** {@code CreateSimulationOverrideRequest.scenario}. */
    public static final List<String> CREATE_SIMULATION_OVERRIDE_REQUEST_SCENARIO_VALUES = List.of(
            "FORCE_DECLINE", "FORCE_ERROR", "INJECT_LATENCY", "FORCE_TIMEOUT",
            "FORCE_RATE_LIMIT", "DELAY_SETTLEMENT", "DUPLICATE_WEBHOOKS", "WEBHOOK_FAILURE");

    /** {@code EventResponse.mode}. */
    public static final List<String> EVENT_RESPONSE_MODE_VALUES = List.of("test", "live");

    /** {@code PaymentResponse.mode}. */
    public static final List<String> PAYMENT_RESPONSE_MODE_VALUES = List.of("test", "live");

    /** {@code RefundResponse.mode}. */
    public static final List<String> REFUND_RESPONSE_MODE_VALUES = List.of("test", "live");

    /** {@code RequestLogResponse.mode}. */
    public static final List<String> REQUEST_LOG_RESPONSE_MODE_VALUES = List.of("test", "live");

    /** {@code WebhookDeliveryAttemptResponse.outcome}. */
    public static final List<String> WEBHOOK_DELIVERY_ATTEMPT_RESPONSE_OUTCOME_VALUES = List.of(
            "SUCCEEDED", "FAILED_STATUS", "FAILED_TRANSPORT", "BLOCKED");

    /** {@code WebhookDeliveryResponse.status}. */
    public static final List<String> WEBHOOK_DELIVERY_RESPONSE_STATUS_VALUES = List.of(
            "PENDING", "DELIVERED", "DEAD_LETTERED");

    /** {@code WebhookEndpointResponse.disabledReason}. */
    public static final List<String> WEBHOOK_ENDPOINT_RESPONSE_DISABLED_REASON_VALUES = List.of("CONSECUTIVE_FAILURES");
}
