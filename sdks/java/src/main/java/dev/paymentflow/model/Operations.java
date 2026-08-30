package dev.paymentflow.model;

import java.util.List;
import java.util.Map;

/**
 * Every published operation, in the form a hand-written client needs to address it — and no
 * more. Verified against {@code ../shared/fixtures/operations.json} by {@code ContractParityTest}.
 *
 * <p>{@code requiredHeaders} is the field that matters most: it is {@code Idempotency-Key} on
 * exactly the five payment mutations the platform deduplicates on, and empty everywhere else.
 * The transport reads it from here rather than from a list of its own, because a hand-kept copy
 * of "which calls need a key" keeps answering the old question after the contract moves, and the
 * failure mode of that is a duplicated charge.
 */
public final class Operations {

    private Operations() {}

    /** One published operation, addressed by the operation id M21.7 made unique. */
    public record OperationDescriptor(
            String id,
            String method,
            String path,
            String tag,
            String summary,
            String successStatus,
            List<String> queryParameters,
            List<String> requiredHeaders,
            boolean hasRequestBody) {

        /** Whether this SDK may safely replay the operation after a failure with no response. */
        public boolean replayable() {
            return method.equals("GET") || method.equals("DELETE") || requiredHeaders.contains("Idempotency-Key");
        }
    }

    private static OperationDescriptor op(String id, String method, String path, String tag, String summary,
                                          String successStatus, List<String> query, List<String> headers,
                                          boolean hasBody) {
        return new OperationDescriptor(id, method, path, tag, summary, successStatus,
                List.copyOf(query), List.copyOf(headers), hasBody);
    }

    private static final List<String> NONE = List.of();
    private static final List<String> IDEMPOTENCY = List.of("Idempotency-Key");

    public static final OperationDescriptor GET_PAYMENT_ANALYTICS = op("getPaymentAnalytics", "GET",
            "/v1/analytics/payments", "Analytics", "Summarize payment activity", "200",
            List.of("from", "to"), NONE, false);

    public static final OperationDescriptor GET_BALANCE = op("getBalance", "GET",
            "/v1/balance", "Balance", "Retrieve your balance", "200", NONE, NONE, false);

    public static final OperationDescriptor LIST_BALANCE_TRANSACTIONS = op("listBalanceTransactions", "GET",
            "/v1/balance_transactions", "Balance transactions", "List balance transactions", "200",
            List.of("limit", "starting_after", "created_after", "created_before"), NONE, false);

    public static final OperationDescriptor LIST_EVENTS = op("listEvents", "GET",
            "/v1/events", "Events", "List events", "200",
            List.of("limit", "starting_after", "type", "created_after", "created_before"), NONE, false);

    public static final OperationDescriptor GET_EVENT = op("getEvent", "GET",
            "/v1/events/{id}", "Events", "Retrieve an event", "200", NONE, NONE, false);

    public static final OperationDescriptor LIST_PAYMENTS = op("listPayments", "GET",
            "/v1/payments", "Payments", "List payments", "200",
            List.of("limit", "starting_after", "status", "currency", "amount_min", "amount_max",
                    "created_after", "created_before", "expand", "metadata"), NONE, false);

    public static final OperationDescriptor CREATE_PAYMENT = op("createPayment", "POST",
            "/v1/payments", "Payments", "Create a payment", "201", NONE, IDEMPOTENCY, true);

    public static final OperationDescriptor GET_PAYMENT = op("getPayment", "GET",
            "/v1/payments/{id}", "Payments", "Retrieve a payment", "200", List.of("expand"), NONE, false);

    public static final OperationDescriptor AUTHORIZE_PAYMENT = op("authorizePayment", "POST",
            "/v1/payments/{id}/authorize", "Payments", "Authorize a payment", "200", NONE, IDEMPOTENCY, false);

    public static final OperationDescriptor CAPTURE_PAYMENT = op("capturePayment", "POST",
            "/v1/payments/{id}/capture", "Payments", "Capture an authorized payment", "200", NONE, IDEMPOTENCY, false);

    public static final OperationDescriptor REFUND_PAYMENT = op("refundPayment", "POST",
            "/v1/payments/{id}/refund", "Payments", "Refund a captured payment", "200", NONE, IDEMPOTENCY, true);

    public static final OperationDescriptor VOID_PAYMENT = op("voidPayment", "POST",
            "/v1/payments/{id}/void", "Payments", "Void a payment", "200", NONE, IDEMPOTENCY, false);

    public static final OperationDescriptor LIST_REFUNDS = op("listRefunds", "GET",
            "/v1/refunds", "Refunds", "List refunds", "200",
            List.of("limit", "starting_after", "payment", "status", "created_after", "created_before", "metadata"),
            NONE, false);

    public static final OperationDescriptor GET_REFUND = op("getRefund", "GET",
            "/v1/refunds/{id}", "Refunds", "Retrieve a refund", "200", NONE, NONE, false);

    public static final OperationDescriptor LIST_REQUEST_LOGS = op("listRequestLogs", "GET",
            "/v1/request_logs", "Request logs", "List API request logs", "200",
            List.of("limit", "starting_after", "created_after", "created_before", "status_code", "method"),
            NONE, false);

    public static final OperationDescriptor LIST_TEST_CARDS = op("listTestCards", "GET",
            "/v1/test/cards", "Test cards", "List the test cards", "200", NONE, NONE, false);

    public static final OperationDescriptor LIST_SANDBOX_DECISIONS = op("listSandboxDecisions", "GET",
            "/v1/test/decisions", "Decisions", "List sandbox decisions", "200",
            List.of("page", "size", "sort"), NONE, false);

    public static final OperationDescriptor LIST_SANDBOX_DECISIONS_FOR_PAYMENT = op("listSandboxDecisionsForPayment",
            "GET", "/v1/test/decisions/payments/{paymentId}", "Decisions", "List the decisions for one payment",
            "200", NONE, NONE, false);

    public static final OperationDescriptor CREATE_SIMULATION_OVERRIDE = op("createSimulationOverride", "POST",
            "/v1/test/simulations", "Simulations", "Force a sandbox behaviour", "201", NONE, NONE, true);

    public static final OperationDescriptor REVOKE_ACTIVE_SIMULATION_OVERRIDE = op("revokeActiveSimulationOverride",
            "DELETE", "/v1/test/simulations/active", "Simulations", "Revoke the active override", "204",
            NONE, NONE, false);

    public static final OperationDescriptor GET_ACTIVE_SIMULATION_OVERRIDE = op("getActiveSimulationOverride", "GET",
            "/v1/test/simulations/active", "Simulations", "Retrieve the active override", "200", NONE, NONE, false);

    public static final OperationDescriptor GET_USAGE = op("getUsage", "GET",
            "/v1/usage", "Usage", "Summarize API usage", "200", List.of("from", "to"), NONE, false);

    public static final OperationDescriptor LIST_WEBHOOK_DELIVERIES = op("listWebhookDeliveries", "GET",
            "/v1/webhook_deliveries", "Webhook deliveries", "List webhook deliveries", "200",
            List.of("page", "size", "sort"), NONE, false);

    public static final OperationDescriptor GET_WEBHOOK_DELIVERY = op("getWebhookDelivery", "GET",
            "/v1/webhook_deliveries/{id}", "Webhook deliveries", "Retrieve a webhook delivery", "200",
            NONE, NONE, false);

    public static final OperationDescriptor REPLAY_WEBHOOK_DELIVERY = op("replayWebhookDelivery", "POST",
            "/v1/webhook_deliveries/{id}/replay", "Webhook deliveries", "Replay a webhook delivery", "201",
            NONE, NONE, false);

    public static final OperationDescriptor LIST_WEBHOOK_ENDPOINTS = op("listWebhookEndpoints", "GET",
            "/v1/webhook_endpoints", "Webhook endpoints", "List your webhook endpoints", "200", NONE, NONE, false);

    public static final OperationDescriptor CREATE_WEBHOOK_ENDPOINT = op("createWebhookEndpoint", "POST",
            "/v1/webhook_endpoints", "Webhook endpoints", "Register a webhook endpoint", "201", NONE, NONE, true);

    public static final OperationDescriptor DELETE_WEBHOOK_ENDPOINT = op("deleteWebhookEndpoint", "DELETE",
            "/v1/webhook_endpoints/{id}", "Webhook endpoints", "Delete a webhook endpoint", "204", NONE, NONE, false);

    public static final OperationDescriptor GET_WEBHOOK_ENDPOINT = op("getWebhookEndpoint", "GET",
            "/v1/webhook_endpoints/{id}", "Webhook endpoints", "Retrieve a webhook endpoint", "200", NONE, NONE, false);

    public static final OperationDescriptor UPDATE_WEBHOOK_ENDPOINT = op("updateWebhookEndpoint", "PATCH",
            "/v1/webhook_endpoints/{id}", "Webhook endpoints", "Update a webhook endpoint", "200", NONE, NONE, true);

    public static final OperationDescriptor ROTATE_WEBHOOK_ENDPOINT_SECRET = op("rotateWebhookEndpointSecret", "POST",
            "/v1/webhook_endpoints/{id}/rotate_secret", "Webhook endpoints", "Rotate an endpoint's signing secret",
            "200", NONE, NONE, false);

    /** Every descriptor, keyed by its operation id. For iteration and for the parity test. */
    public static final Map<String, OperationDescriptor> ALL = Map.ofEntries(
            Map.entry(GET_PAYMENT_ANALYTICS.id(), GET_PAYMENT_ANALYTICS),
            Map.entry(GET_BALANCE.id(), GET_BALANCE),
            Map.entry(LIST_BALANCE_TRANSACTIONS.id(), LIST_BALANCE_TRANSACTIONS),
            Map.entry(LIST_EVENTS.id(), LIST_EVENTS),
            Map.entry(GET_EVENT.id(), GET_EVENT),
            Map.entry(LIST_PAYMENTS.id(), LIST_PAYMENTS),
            Map.entry(CREATE_PAYMENT.id(), CREATE_PAYMENT),
            Map.entry(GET_PAYMENT.id(), GET_PAYMENT),
            Map.entry(AUTHORIZE_PAYMENT.id(), AUTHORIZE_PAYMENT),
            Map.entry(CAPTURE_PAYMENT.id(), CAPTURE_PAYMENT),
            Map.entry(REFUND_PAYMENT.id(), REFUND_PAYMENT),
            Map.entry(VOID_PAYMENT.id(), VOID_PAYMENT),
            Map.entry(LIST_REFUNDS.id(), LIST_REFUNDS),
            Map.entry(GET_REFUND.id(), GET_REFUND),
            Map.entry(LIST_REQUEST_LOGS.id(), LIST_REQUEST_LOGS),
            Map.entry(LIST_TEST_CARDS.id(), LIST_TEST_CARDS),
            Map.entry(LIST_SANDBOX_DECISIONS.id(), LIST_SANDBOX_DECISIONS),
            Map.entry(LIST_SANDBOX_DECISIONS_FOR_PAYMENT.id(), LIST_SANDBOX_DECISIONS_FOR_PAYMENT),
            Map.entry(CREATE_SIMULATION_OVERRIDE.id(), CREATE_SIMULATION_OVERRIDE),
            Map.entry(REVOKE_ACTIVE_SIMULATION_OVERRIDE.id(), REVOKE_ACTIVE_SIMULATION_OVERRIDE),
            Map.entry(GET_ACTIVE_SIMULATION_OVERRIDE.id(), GET_ACTIVE_SIMULATION_OVERRIDE),
            Map.entry(GET_USAGE.id(), GET_USAGE),
            Map.entry(LIST_WEBHOOK_DELIVERIES.id(), LIST_WEBHOOK_DELIVERIES),
            Map.entry(GET_WEBHOOK_DELIVERY.id(), GET_WEBHOOK_DELIVERY),
            Map.entry(REPLAY_WEBHOOK_DELIVERY.id(), REPLAY_WEBHOOK_DELIVERY),
            Map.entry(LIST_WEBHOOK_ENDPOINTS.id(), LIST_WEBHOOK_ENDPOINTS),
            Map.entry(CREATE_WEBHOOK_ENDPOINT.id(), CREATE_WEBHOOK_ENDPOINT),
            Map.entry(DELETE_WEBHOOK_ENDPOINT.id(), DELETE_WEBHOOK_ENDPOINT),
            Map.entry(GET_WEBHOOK_ENDPOINT.id(), GET_WEBHOOK_ENDPOINT),
            Map.entry(UPDATE_WEBHOOK_ENDPOINT.id(), UPDATE_WEBHOOK_ENDPOINT),
            Map.entry(ROTATE_WEBHOOK_ENDPOINT_SECRET.id(), ROTATE_WEBHOOK_ENDPOINT_SECRET));
}
