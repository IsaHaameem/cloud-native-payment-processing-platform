package paymentflow

// Generated-equivalent. Hand-transcribed from docs/openapi.yaml and verified against
// ../shared/fixtures/contract.json by parity_test.go.

const (
	// APIVersion is the dated API revision this SDK sends as PaymentFlow-Version unless a
	// caller overrides it with WithAPIVersion. It is deliberately not this package's own
	// Version: an SDK bug fix is a patch release against an unchanged API.
	APIVersion = "2026-08-01"

	// DefaultBaseURL is the published host. Override with WithBaseURL.
	DefaultBaseURL = "https://api.paymentflow.dev"

	// APITitle is the title of the contract this SDK implements.
	APITitle = "PaymentFlow API"
)
