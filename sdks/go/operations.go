package paymentflow

// operationDescriptor is one published operation, in the form the transport needs to address
// it — and no more. requiredHeaders is the field that matters most: it is "Idempotency-Key" on
// exactly the five payment mutations the platform deduplicates on, and empty everywhere else.
// The transport reads it from here rather than from a list of its own, because a hand-kept copy
// of "which calls need a key" keeps answering the old question after the contract moves, and
// the failure mode of that is a duplicated charge.
type operationDescriptor struct {
	ID              string
	Method          string
	Path            string
	Tag             string
	Summary         string
	SuccessStatus   string
	QueryParameters []string
	RequiredHeaders []string
	HasRequestBody  bool
}

// replayable reports whether the SDK may safely retry the operation after a failure with no
// response: GET and DELETE are idempotent by HTTP, and anything carrying an Idempotency-Key is
// deduplicated by the platform.
func (o operationDescriptor) replayable() bool {
	if o.Method == "GET" || o.Method == "DELETE" {
		return true
	}
	for _, h := range o.RequiredHeaders {
		if h == "Idempotency-Key" {
			return true
		}
	}
	return false
}

var (
	idempotency = []string{"Idempotency-Key"}
	noHeaders   []string
)

// operations is every published operation, keyed by its operation id. Verified against
// ../shared/fixtures/operations.json by parity_test.go.
var operations = map[string]operationDescriptor{
	"getPaymentAnalytics": {"getPaymentAnalytics", "GET", "/v1/analytics/payments", "Analytics",
		"Summarize payment activity", "200", []string{"from", "to"}, noHeaders, false},
	"getBalance": {"getBalance", "GET", "/v1/balance", "Balance",
		"Retrieve your balance", "200", nil, noHeaders, false},
	"listBalanceTransactions": {"listBalanceTransactions", "GET", "/v1/balance_transactions",
		"Balance transactions", "List balance transactions", "200",
		[]string{"limit", "starting_after", "created_after", "created_before"}, noHeaders, false},
	"listEvents": {"listEvents", "GET", "/v1/events", "Events", "List events", "200",
		[]string{"limit", "starting_after", "type", "created_after", "created_before"}, noHeaders, false},
	"getEvent": {"getEvent", "GET", "/v1/events/{id}", "Events", "Retrieve an event", "200",
		nil, noHeaders, false},
	"listPayments": {"listPayments", "GET", "/v1/payments", "Payments", "List payments", "200",
		[]string{"limit", "starting_after", "status", "currency", "amount_min", "amount_max",
			"created_after", "created_before", "expand", "metadata"}, noHeaders, false},
	"createPayment": {"createPayment", "POST", "/v1/payments", "Payments", "Create a payment", "201",
		nil, idempotency, true},
	"getPayment": {"getPayment", "GET", "/v1/payments/{id}", "Payments", "Retrieve a payment", "200",
		[]string{"expand"}, noHeaders, false},
	"authorizePayment": {"authorizePayment", "POST", "/v1/payments/{id}/authorize", "Payments",
		"Authorize a payment", "200", nil, idempotency, false},
	"capturePayment": {"capturePayment", "POST", "/v1/payments/{id}/capture", "Payments",
		"Capture an authorized payment", "200", nil, idempotency, false},
	"refundPayment": {"refundPayment", "POST", "/v1/payments/{id}/refund", "Payments",
		"Refund a captured payment", "200", nil, idempotency, true},
	"voidPayment": {"voidPayment", "POST", "/v1/payments/{id}/void", "Payments",
		"Void a payment", "200", nil, idempotency, false},
	"listRefunds": {"listRefunds", "GET", "/v1/refunds", "Refunds", "List refunds", "200",
		[]string{"limit", "starting_after", "payment", "status", "created_after", "created_before", "metadata"},
		noHeaders, false},
	"getRefund": {"getRefund", "GET", "/v1/refunds/{id}", "Refunds", "Retrieve a refund", "200",
		nil, noHeaders, false},
	"listRequestLogs": {"listRequestLogs", "GET", "/v1/request_logs", "Request logs",
		"List API request logs", "200",
		[]string{"limit", "starting_after", "created_after", "created_before", "status_code", "method"},
		noHeaders, false},
	"listTestCards": {"listTestCards", "GET", "/v1/test/cards", "Test cards", "List the test cards",
		"200", nil, noHeaders, false},
	"listSandboxDecisions": {"listSandboxDecisions", "GET", "/v1/test/decisions", "Decisions",
		"List sandbox decisions", "200", []string{"page", "size", "sort"}, noHeaders, false},
	"listSandboxDecisionsForPayment": {"listSandboxDecisionsForPayment", "GET",
		"/v1/test/decisions/payments/{paymentId}", "Decisions", "List the decisions for one payment",
		"200", nil, noHeaders, false},
	"createSimulationOverride": {"createSimulationOverride", "POST", "/v1/test/simulations",
		"Simulations", "Force a sandbox behaviour", "201", nil, noHeaders, true},
	"revokeActiveSimulationOverride": {"revokeActiveSimulationOverride", "DELETE",
		"/v1/test/simulations/active", "Simulations", "Revoke the active override", "204",
		nil, noHeaders, false},
	"getActiveSimulationOverride": {"getActiveSimulationOverride", "GET", "/v1/test/simulations/active",
		"Simulations", "Retrieve the active override", "200", nil, noHeaders, false},
	"getUsage": {"getUsage", "GET", "/v1/usage", "Usage", "Summarize API usage", "200",
		[]string{"from", "to"}, noHeaders, false},
	"listWebhookDeliveries": {"listWebhookDeliveries", "GET", "/v1/webhook_deliveries",
		"Webhook deliveries", "List webhook deliveries", "200",
		[]string{"page", "size", "sort"}, noHeaders, false},
	"getWebhookDelivery": {"getWebhookDelivery", "GET", "/v1/webhook_deliveries/{id}",
		"Webhook deliveries", "Retrieve a webhook delivery", "200", nil, noHeaders, false},
	"replayWebhookDelivery": {"replayWebhookDelivery", "POST", "/v1/webhook_deliveries/{id}/replay",
		"Webhook deliveries", "Replay a webhook delivery", "201", nil, noHeaders, false},
	"listWebhookEndpoints": {"listWebhookEndpoints", "GET", "/v1/webhook_endpoints",
		"Webhook endpoints", "List your webhook endpoints", "200", nil, noHeaders, false},
	"createWebhookEndpoint": {"createWebhookEndpoint", "POST", "/v1/webhook_endpoints",
		"Webhook endpoints", "Register a webhook endpoint", "201", nil, noHeaders, true},
	"deleteWebhookEndpoint": {"deleteWebhookEndpoint", "DELETE", "/v1/webhook_endpoints/{id}",
		"Webhook endpoints", "Delete a webhook endpoint", "204", nil, noHeaders, false},
	"getWebhookEndpoint": {"getWebhookEndpoint", "GET", "/v1/webhook_endpoints/{id}",
		"Webhook endpoints", "Retrieve a webhook endpoint", "200", nil, noHeaders, false},
	"updateWebhookEndpoint": {"updateWebhookEndpoint", "PATCH", "/v1/webhook_endpoints/{id}",
		"Webhook endpoints", "Update a webhook endpoint", "200", nil, noHeaders, true},
	"rotateWebhookEndpointSecret": {"rotateWebhookEndpointSecret", "POST",
		"/v1/webhook_endpoints/{id}/rotate_secret", "Webhook endpoints",
		"Rotate an endpoint's signing secret", "200", nil, noHeaders, false},
}

func op(id string) operationDescriptor {
	d, ok := operations[id]
	if !ok {
		panic("paymentflow: unknown operation id " + id) // a programming error in this package
	}
	return d
}
