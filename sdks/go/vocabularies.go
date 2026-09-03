package paymentflow

// The open vocabularies the contract documents.
//
// Every one of these is a field typed as string, not a Go named-constant enum. §9 requires a
// client to tolerate an enum value it has never heard of — that is what makes "a new status
// value is additive, not breaking" true rather than aspirational — so the values live here as
// slices to recognise a value against when you want to know whether you handle it, never a set
// to validate against. Verified against ../shared/fixtures/enums.json by parity_test.go.
var (
	// APIErrorTypeValues is the closed set of ApiError.type this SDK maps to error types.
	APIErrorTypeValues = []string{
		"authentication_error", "permission_error", "invalid_request_error",
		"idempotency_error", "rate_limit_error", "api_error",
	}
	// BalanceTransactionResponseDirectionValues — BalanceTransaction.Direction.
	BalanceTransactionResponseDirectionValues = []string{"DEBIT", "CREDIT"}
	// BalanceTransactionResponseModeValues — BalanceTransaction.Mode.
	BalanceTransactionResponseModeValues = []string{"test", "live"}
	// CreateSimulationOverrideRequestScenarioValues — the sandbox scenarios.
	CreateSimulationOverrideRequestScenarioValues = []string{
		"FORCE_DECLINE", "FORCE_ERROR", "INJECT_LATENCY", "FORCE_TIMEOUT",
		"FORCE_RATE_LIMIT", "DELAY_SETTLEMENT", "DUPLICATE_WEBHOOKS", "WEBHOOK_FAILURE",
	}
	// EventResponseModeValues — Event.Mode.
	EventResponseModeValues = []string{"test", "live"}
	// PaymentResponseModeValues — Payment.Mode.
	PaymentResponseModeValues = []string{"test", "live"}
	// RefundResponseModeValues — Refund.Mode.
	RefundResponseModeValues = []string{"test", "live"}
	// RequestLogResponseModeValues — RequestLog.Mode.
	RequestLogResponseModeValues = []string{"test", "live"}
	// WebhookDeliveryAttemptResponseOutcomeValues — WebhookDeliveryAttempt.Outcome.
	WebhookDeliveryAttemptResponseOutcomeValues = []string{
		"SUCCEEDED", "FAILED_STATUS", "FAILED_TRANSPORT", "BLOCKED",
	}
	// WebhookDeliveryResponseStatusValues — WebhookDelivery.Status.
	WebhookDeliveryResponseStatusValues = []string{"PENDING", "DELIVERED", "DEAD_LETTERED"}
	// WebhookEndpointResponseDisabledReasonValues — WebhookEndpoint.DisabledReason.
	WebhookEndpointResponseDisabledReasonValues = []string{"CONSECUTIVE_FAILURES"}
)

// vocabulariesByFixtureName maps the fixture's enum name to the slice above, so parity_test.go
// can check every one without transcribing the derivation.
var vocabulariesByFixtureName = map[string][]string{
	"ApiErrorType":                            APIErrorTypeValues,
	"BalanceTransactionResponseDirection":     BalanceTransactionResponseDirectionValues,
	"BalanceTransactionResponseMode":          BalanceTransactionResponseModeValues,
	"CreateSimulationOverrideRequestScenario": CreateSimulationOverrideRequestScenarioValues,
	"EventResponseMode":                       EventResponseModeValues,
	"PaymentResponseMode":                     PaymentResponseModeValues,
	"RefundResponseMode":                      RefundResponseModeValues,
	"RequestLogResponseMode":                  RequestLogResponseModeValues,
	"WebhookDeliveryAttemptResponseOutcome":   WebhookDeliveryAttemptResponseOutcomeValues,
	"WebhookDeliveryResponseStatus":           WebhookDeliveryResponseStatusValues,
	"WebhookEndpointResponseDisabledReason":   WebhookEndpointResponseDisabledReasonValues,
}
