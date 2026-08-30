package paymentflow

// Generated-equivalent: the contract's response shapes. Hand-transcribed from docs/openapi.yaml
// and verified field-for-field against ../shared/fixtures/models.json by parity_test.go.
//
// Fields the contract documents as *explicitly null* (a value that was measured and has no
// answer, distinct from a field this revision does not have) are pointers, so nil and absent
// are the same and a real null is not lost. Everything else is a value type: encoding/json
// leaves an absent field at its zero value, which is what §9's forward-compatibility promise
// expects a client to tolerate. Amounts are int64 — there are no floating-point amounts
// anywhere in this API. Timestamps stay as RFC 3339 strings, the same choice the other three
// SDKs make: it is what the wire carries.

// FieldError is one field-level validation failure, as carried in an error body's errors array.
type FieldError struct {
	Field   string `json:"field"`
	Message string `json:"message"`
}

// CurrencyBalance is your balance in one currency, in that currency's minor unit. Available is
// captured and owed to you, net of refunds; Pending is authorized but not yet captured.
type CurrencyBalance struct {
	AvailableMinor int64  `json:"availableMinor"`
	Currency       string `json:"currency"`
	PendingMinor   int64  `json:"pendingMinor"`
}

// Balance is your current balance, one entry per currency you hold a balance in.
type Balance struct {
	Balances []CurrencyBalance `json:"balances"`
	Object   string            `json:"object"`
}

// BalanceTransaction is one entry in your balance ledger. AmountMinor is always positive —
// Direction ("DEBIT"/"CREDIT", see BalanceTransactionResponseDirectionValues) carries the sign.
type BalanceTransaction struct {
	AccountType string `json:"accountType"`
	AmountMinor int64  `json:"amountMinor"`
	CreatedAt   string `json:"createdAt"`
	Currency    string `json:"currency"`
	Direction   string `json:"direction"`
	EventType   string `json:"eventType"`
	ID          string `json:"id"`
	Mode        string `json:"mode"`
	Object      string `json:"object"`
	PaymentID   string `json:"paymentId"`
}

// AnalyticsBucket is one hour of payment activity, for a single currency.
type AnalyticsBucket struct {
	AuthorizedCount          int64  `json:"authorizedCount"`
	BucketStart              string `json:"bucketStart"`
	CapturedCount            int64  `json:"capturedCount"`
	CreatedCount             int64  `json:"createdCount"`
	Currency                 string `json:"currency"`
	FailedCount              int64  `json:"failedCount"`
	Object                   string `json:"object"`
	RefundedCount            int64  `json:"refundedCount"`
	TotalCapturedAmountMinor int64  `json:"totalCapturedAmountMinor"`
	TotalRefundedAmountMinor int64  `json:"totalRefundedAmountMinor"`
	VoidedCount              int64  `json:"voidedCount"`
}

// AnalyticsSummary is payment activity summarized over a window. SuccessRate is
// authorizedCount / (authorizedCount + failedCount), and is nil — never 0 — when nothing was
// attempted in the window: a rate over zero attempts is unknown, not zero.
type AnalyticsSummary struct {
	AuthorizedCount          int64             `json:"authorizedCount"`
	Buckets                  []AnalyticsBucket `json:"buckets"`
	CapturedCount            int64             `json:"capturedCount"`
	CreatedCount             int64             `json:"createdCount"`
	FailedCount              int64             `json:"failedCount"`
	From                     string            `json:"from"`
	Object                   string            `json:"object"`
	RefundedCount            int64             `json:"refundedCount"`
	SuccessRate              *float64          `json:"successRate"`
	To                       string            `json:"to"`
	TotalCapturedAmountMinor int64             `json:"totalCapturedAmountMinor"`
	TotalRefundedAmountMinor int64             `json:"totalRefundedAmountMinor"`
	VoidedCount              int64             `json:"voidedCount"`
}

// DecisionLogEntry is one sandbox decision. Source is why the outcome was what it was — the
// test card's catalogue entry, an active simulation override, or the mode's default.
type DecisionLogEntry struct {
	CreatedAt         string `json:"createdAt"`
	DecisionKey       string `json:"decisionKey"`
	DeclineCode       string `json:"declineCode"`
	DeferredDelayMs   int64  `json:"deferredDelayMs"`
	DeferredOperation string `json:"deferredOperation"`
	ErrorCode         string `json:"errorCode"`
	LatencyMs         int64  `json:"latencyMs"`
	Operation         string `json:"operation"`
	Outcome           string `json:"outcome"`
	OverrideID        string `json:"overrideId"`
	PaymentID         string `json:"paymentId"`
	Source            string `json:"source"`
}

// Event is one event from your event log. ID (evt_ + 32 hex) is byte-identical to the id in
// the webhook body for the same event. Data is kept verbatim; branch on Type before reading it.
type Event struct {
	Created string         `json:"created"`
	Data    map[string]any `json:"data"`
	ID      string         `json:"id"`
	Mode    string         `json:"mode"`
	Object  string         `json:"object"`
	Type    string         `json:"type"`
}

// Payment is a payment. Status is lowercase snake_case as of API revision 2026-08-01. Refunds
// is populated only when you ask with Expand: "refunds", and is nil otherwise (not empty).
type Payment struct {
	AmountMinor         int64             `json:"amountMinor"`
	CapturedAmountMinor int64             `json:"capturedAmountMinor"`
	CreatedAt           string            `json:"createdAt"`
	Currency            string            `json:"currency"`
	Description         string            `json:"description"`
	FailureReason       string            `json:"failureReason"`
	ID                  string            `json:"id"`
	MerchantID          string            `json:"merchantId"`
	Metadata            map[string]string `json:"metadata"`
	Mode                string            `json:"mode"`
	Object              string            `json:"object"`
	PaymentMethodToken  string            `json:"paymentMethodToken"`
	RefundedAmountMinor int64             `json:"refundedAmountMinor"`
	Refunds             []Refund          `json:"refunds"`
	Status              string            `json:"status"`
	UpdatedAt           string            `json:"updatedAt"`
}

// Refund is a refund issued against a payment. Created through Payments.Refund, which returns
// the Payment — the refund is the newest entry in its Refunds slice.
type Refund struct {
	AmountMinor   int64             `json:"amountMinor"`
	CreatedAt     string            `json:"createdAt"`
	Currency      string            `json:"currency"`
	FailureReason string            `json:"failureReason"`
	ID            string            `json:"id"`
	MerchantID    string            `json:"merchantId"`
	Metadata      map[string]string `json:"metadata"`
	Mode          string            `json:"mode"`
	Object        string            `json:"object"`
	PaymentID     string            `json:"paymentId"`
	Reason        string            `json:"reason"`
	Status        string            `json:"status"`
	UpdatedAt     string            `json:"updatedAt"`
}

// RequestLog is one row of your API request log. Bodies and headers are redacted at the edge
// before they are ever stored. DurationMs is server time only, excluding the network.
type RequestLog struct {
	ClientIP       string            `json:"clientIp"`
	CorrelationID  string            `json:"correlationId"`
	DurationMs     int64             `json:"durationMs"`
	ErrorCode      string            `json:"errorCode"`
	ID             string            `json:"id"`
	KeyID          string            `json:"keyId"`
	Method         string            `json:"method"`
	Mode           string            `json:"mode"`
	Object         string            `json:"object"`
	OccurredAt     string            `json:"occurredAt"`
	Path           string            `json:"path"`
	QueryString    string            `json:"queryString"`
	RequestBody    string            `json:"requestBody"`
	RequestHeaders map[string]string `json:"requestHeaders"`
	RequestID      string            `json:"requestId"`
	ResponseBody   string            `json:"responseBody"`
	StatusCode     int64             `json:"statusCode"`
	UserAgent      string            `json:"userAgent"`
}

// SimulationOverride is an active sandbox simulation override. Several fields are explicitly
// nil rather than absent: the code/latency fields for a scenario that does not use them,
// ExpiresAt for a count-bounded override, RemainingCount for a time-bounded one, and
// EnactedFrom when the decision engine enforces the scenario now.
type SimulationOverride struct {
	DeclineCode    *string `json:"declineCode"`
	EnactedFrom    *string `json:"enactedFrom"`
	ErrorCode      *string `json:"errorCode"`
	ExpiresAt      *string `json:"expiresAt"`
	ID             string  `json:"id"`
	LatencyMs      *int64  `json:"latencyMs"`
	RemainingCount *int64  `json:"remainingCount"`
	Scenario       string  `json:"scenario"`
}

// TestCard is one test-mode payment-method token and the behaviour it forces. Authorization and
// capture can behave differently on purpose (Outcome vs CaptureBehaviour).
type TestCard struct {
	Brand            string  `json:"brand"`
	CaptureBehaviour string  `json:"captureBehaviour"`
	DeclineCode      *string `json:"declineCode"`
	DeferredDelayMs  *int64  `json:"deferredDelayMs"`
	Description      string  `json:"description"`
	ErrorCode        *string `json:"errorCode"`
	LatencyMs        int64   `json:"latencyMs"`
	Outcome          string  `json:"outcome"`
	RefundBehaviour  string  `json:"refundBehaviour"`
	Token            string  `json:"token"`
}

// UsageBucket is one day of API usage for one key and one route pattern. The percentile and
// mean durations are nil when the bucket had no traffic. KeyID is nil for traffic made with a
// key since deleted.
type UsageBucket struct {
	ClientErrors   int64    `json:"clientErrors"`
	Day            string   `json:"day"`
	KeyID          *string  `json:"keyId"`
	MaxDurationMs  int64    `json:"maxDurationMs"`
	MeanDurationMs *float64 `json:"meanDurationMs"`
	P50DurationMs  *float64 `json:"p50DurationMs"`
	P95DurationMs  *float64 `json:"p95DurationMs"`
	P99DurationMs  *float64 `json:"p99DurationMs"`
	Requests       int64    `json:"requests"`
	Route          string   `json:"route"`
	ServerErrors   int64    `json:"serverErrors"`
}

// UsageSummary is API usage summarized over a day range. TotalClientErrors are your rejected
// requests (4xx); TotalServerErrors are this platform's failures (5xx).
type UsageSummary struct {
	Buckets           []UsageBucket `json:"buckets"`
	From              string        `json:"from"`
	To                string        `json:"to"`
	TotalClientErrors int64         `json:"totalClientErrors"`
	TotalRequests     int64         `json:"totalRequests"`
	TotalServerErrors int64         `json:"totalServerErrors"`
}

// WebhookDeliveryAttempt is one attempt at delivering a webhook. RequestBody is byte-for-byte
// what the signature was computed over; RequestHeaders includes the PaymentFlow-Signature the
// platform sent (a signature is not a secret). Error is set only when the receiver was never
// reached; ResponseStatus only when it was.
type WebhookDeliveryAttempt struct {
	AttemptNumber   int64  `json:"attemptNumber"`
	AttemptedAt     string `json:"attemptedAt"`
	DurationMs      int64  `json:"durationMs"`
	Error           string `json:"error"`
	ID              string `json:"id"`
	Outcome         string `json:"outcome"`
	RequestBody     string `json:"requestBody"`
	RequestHeaders  string `json:"requestHeaders"`
	RequestURL      string `json:"requestUrl"`
	ResponseBody    string `json:"responseBody"`
	ResponseHeaders string `json:"responseHeaders"`
	ResponseStatus  int64  `json:"responseStatus"`
}

// WebhookDelivery is one webhook delivery, with every attempt — the request sent and the
// response received. EventID is the same evt_ id readable at /v1/events.
type WebhookDelivery struct {
	AttemptCount           int64                    `json:"attemptCount"`
	Attempts               []WebhookDeliveryAttempt `json:"attempts"`
	CreatedAt              string                   `json:"createdAt"`
	EndpointID             string                   `json:"endpointId"`
	EventID                string                   `json:"eventId"`
	EventType              string                   `json:"eventType"`
	ID                     string                   `json:"id"`
	LastAttemptedAt        string                   `json:"lastAttemptedAt"`
	NextAttemptAt          string                   `json:"nextAttemptAt"`
	Object                 string                   `json:"object"`
	ReplayedFromDeliveryID string                   `json:"replayedFromDeliveryId"`
	Status                 string                   `json:"status"`
	URL                    string                   `json:"url"`
}

// WebhookEndpoint is a registered webhook endpoint. DisabledReason
// (WebhookEndpointResponseDisabledReasonValues) tells "the platform turned this off" from "I
// turned this off". URL is not updatable. SigningSecretPrefix identifies which secret it holds;
// the full secret is shown once, at creation or rotation, and never again.
type WebhookEndpoint struct {
	APIVersion              string            `json:"apiVersion"`
	ConsecutiveFailureCount int64             `json:"consecutiveFailureCount"`
	CreatedAt               string            `json:"createdAt"`
	Description             string            `json:"description"`
	DisabledAt              string            `json:"disabledAt"`
	DisabledReason          string            `json:"disabledReason"`
	Enabled                 bool              `json:"enabled"`
	EnabledEvents           []string          `json:"enabledEvents"`
	ID                      string            `json:"id"`
	Metadata                map[string]string `json:"metadata"`
	MigratedFromLegacy      bool              `json:"migratedFromLegacy"`
	Object                  string            `json:"object"`
	SigningSecretPrefix     string            `json:"signingSecretPrefix"`
	UpdatedAt               string            `json:"updatedAt"`
	URL                     string            `json:"url"`
}

// WebhookEndpointCreated is what WebhookEndpoints.Create and RotateSecret return: the Endpoint
// in the shape every other read returns it in, plus SigningSecret in full.
//
// The secret is shown exactly once — store it now. Only a hash is kept, so the platform
// genuinely cannot show it again. A lost one is replaced by rotating, not by recovering.
type WebhookEndpointCreated struct {
	Endpoint      WebhookEndpoint `json:"endpoint"`
	SigningSecret string          `json:"signingSecret"`
}
