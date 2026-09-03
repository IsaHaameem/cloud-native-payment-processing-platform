package paymentflow

import (
	"fmt"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

// Client is the PaymentFlow API client. One value holding one resolved configuration and one
// *http.Client, with the eleven resource services hanging off it. Build one per API key and
// share it — a *Client is safe for concurrent use.
type Client struct {
	apiKey     string
	baseURL    string
	apiVersion string
	timeout    time.Duration
	maxRetries int
	httpClient *http.Client

	// Payments — creating, reading and moving payments through their lifecycle.
	Payments *PaymentsService
	// Refunds — reading refunds. They are created through Payments.Refund.
	Refunds *RefundsService
	// Balance — your current balance, per currency.
	Balance *BalanceService
	// BalanceTransactions — the entries that moved your balance.
	BalanceTransactions *BalanceTransactionsService
	// Events — the event log behind your webhooks.
	Events *EventsService
	// Analytics — payment activity summarized over a window.
	Analytics *AnalyticsService
	// RequestLogs — your API calls, as the platform recorded them.
	RequestLogs *RequestLogsService
	// Usage — your API usage, metered per UTC day.
	Usage *UsageService
	// WebhookEndpoints — where events are delivered, and their signing secrets.
	WebhookEndpoints *WebhookEndpointsService
	// WebhookDeliveries — what happened when an event was delivered.
	WebhookDeliveries *WebhookDeliveriesService
	// TestHelpers — the sandbox controls. Test mode only, decided by your key.
	TestHelpers *TestHelpersService
}

// Option configures a Client at construction.
type Option func(*clientOptions)

type clientOptions struct {
	baseURL    string
	apiVersion string
	timeout    time.Duration
	maxRetries *int
	httpClient *http.Client
}

// WithBaseURL overrides the host to call. Defaults to DefaultBaseURL.
func WithBaseURL(u string) Option { return func(o *clientOptions) { o.baseURL = u } }

// WithAPIVersion overrides the dated revision sent as PaymentFlow-Version. Defaults to
// APIVersion — the only one this build's types are known to describe.
func WithAPIVersion(v string) Option { return func(o *clientOptions) { o.apiVersion = v } }

// WithTimeout sets how long one HTTP attempt may take. Default 30s.
func WithTimeout(d time.Duration) Option { return func(o *clientOptions) { o.timeout = d } }

// WithMaxRetries sets how many times a retryable failure is retried. Default 3, so a call makes
// at most four attempts. Zero disables retrying.
func WithMaxRetries(n int) Option { return func(o *clientOptions) { o.maxRetries = &n } }

// WithHTTPClient injects the *http.Client to send with — for tests and proxy configuration.
func WithHTTPClient(c *http.Client) Option { return func(o *clientOptions) { o.httpClient = c } }

// NewClient resolves and validates the configuration and returns a client. apiKey falls back to
// the PAYMENTFLOW_API_KEY environment variable when empty. Validation happens here, so a client
// built with a negative timeout fails on this line rather than on the first payment.
func NewClient(apiKey string, opts ...Option) (*Client, error) {
	o := clientOptions{}
	for _, opt := range opts {
		opt(&o)
	}

	key := apiKey
	if key == "" {
		key = os.Getenv("PAYMENTFLOW_API_KEY")
	}
	if key == "" {
		return nil, fmt.Errorf("paymentflow: no API key. Pass it to NewClient, or set PAYMENTFLOW_API_KEY")
	}
	// A key with surrounding whitespace is the commonest way an Authorization header comes out
	// malformed — it survives a copy-paste out of a dashboard or a .env file and produces a 401
	// that looks like a revoked key. Rejected rather than trimmed: repairing a credential
	// silently hides that the stored one is wrong.
	if strings.TrimSpace(key) != key {
		return nil, fmt.Errorf("paymentflow: the API key has leading or trailing whitespace")
	}

	base := o.baseURL
	if base == "" {
		base = DefaultBaseURL
	}
	base = strings.TrimRight(base, "/")
	parsed, err := url.Parse(base)
	if err != nil || parsed.Scheme == "" || parsed.Host == "" {
		return nil, fmt.Errorf("paymentflow: baseURL is not an absolute URL: %q", base)
	}

	version := o.apiVersion
	if version == "" {
		version = APIVersion
	}

	timeout := o.timeout
	if timeout == 0 {
		timeout = 30 * time.Second
	}
	if timeout < 0 {
		return nil, fmt.Errorf("paymentflow: timeout must be positive")
	}

	maxRetries := 3
	if o.maxRetries != nil {
		maxRetries = *o.maxRetries
	}
	if maxRetries < 0 {
		return nil, fmt.Errorf("paymentflow: maxRetries must not be negative")
	}

	httpClient := o.httpClient
	if httpClient == nil {
		httpClient = &http.Client{}
	}

	c := &Client{
		apiKey:     key,
		baseURL:    base,
		apiVersion: version,
		timeout:    timeout,
		maxRetries: maxRetries,
		httpClient: httpClient,
	}
	t := &transport{client: c}
	c.Payments = &PaymentsService{t}
	c.Refunds = &RefundsService{t}
	c.Balance = &BalanceService{t}
	c.BalanceTransactions = &BalanceTransactionsService{t}
	c.Events = &EventsService{t}
	c.Analytics = &AnalyticsService{t}
	c.RequestLogs = &RequestLogsService{t}
	c.Usage = &UsageService{t}
	c.WebhookEndpoints = &WebhookEndpointsService{t}
	c.WebhookDeliveries = &WebhookDeliveriesService{t}
	c.TestHelpers = &TestHelpersService{t}
	return c, nil
}

// BaseURL is the host this client calls.
func (c *Client) BaseURL() string { return c.baseURL }

// APIVersion is the dated revision this client sends as PaymentFlow-Version.
func (c *Client) APIVersion() string { return c.apiVersion }

// RequestOption is a per-call override, passed as the trailing variadic argument to any
// resource method.
type RequestOption func(*requestConfig)

type requestConfig struct {
	idempotencyKey string
	correlationID  string
	timeout        *time.Duration
	maxRetries     *int
}

// WithIdempotencyKey sends a specific Idempotency-Key. Supply your own when the retry must
// survive your process restarting, not just this SDK's loop.
func WithIdempotencyKey(key string) RequestOption {
	return func(rc *requestConfig) { rc.idempotencyKey = key }
}

// WithCorrelationID sends X-Correlation-Id, echoed back and joined to PaymentFlow's logs.
func WithCorrelationID(id string) RequestOption {
	return func(rc *requestConfig) { rc.correlationID = id }
}

// WithRequestTimeout overrides the client's timeout for this call only.
func WithRequestTimeout(d time.Duration) RequestOption {
	return func(rc *requestConfig) { rc.timeout = &d }
}

// WithRequestMaxRetries overrides the client's retry budget for this call only.
func WithRequestMaxRetries(n int) RequestOption {
	return func(rc *requestConfig) { rc.maxRetries = &n }
}

func newRequestConfig(opts []RequestOption) requestConfig {
	var rc requestConfig
	for _, opt := range opts {
		opt(&rc)
	}
	return rc
}
