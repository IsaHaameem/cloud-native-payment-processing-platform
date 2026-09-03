# PaymentFlow Go SDK

The official PaymentFlow API client for Go. One dependency-free package over the public `/v1`
API: typed models, a typed error hierarchy you branch on with `errors.As`, automatic idempotency
keys that survive a retry, backoff that reads `Retry-After` instead of guessing, transparent
pagination, and webhook signature verification.

> **Status: publish-ready, not tagged.** The code is complete, built, vetted, and tested. Go
> needs no registry account — a release is a git tag `sdks/go/vX.Y.Z` on this repo (see
> [`../PUBLISHING.md`](../PUBLISHING.md)). Until that tag exists, `go get` of a pseudo-version
> works but a clean `@latest` does not.

- **Module:** `github.com/IsaHaameem/cloud-native-payment-processing-platform/sdks/go`
- **Version:** `0.1.0` — SDK semver, independent of the dated API revision
- **API revision:** generated against `2026-08-01`
- **Go:** 1.23 or newer (uses `iter.Seq2` for pagination)

## Install

```bash
go get github.com/IsaHaameem/cloud-native-payment-processing-platform/sdks/go@sdks/go/v0.1.0
```

```go
import paymentflow "github.com/IsaHaameem/cloud-native-payment-processing-platform/sdks/go"
```

## First payment

```go
client, err := paymentflow.NewClient(os.Getenv("PAYMENTFLOW_API_KEY")) // sk_test_… — server-side only
if err != nil {
	log.Fatal(err)
}
ctx := context.Background()

payment, err := client.Payments.Create(ctx, paymentflow.CreatePaymentParams{
	AmountMinor:        1000, // integer minor units: 1000 = 10.00
	Currency:           "USD",
	Description:        "Order A-1234",
	PaymentMethodToken: "pm_card_visa",
})

authorized, err := client.Payments.Authorize(ctx, payment.ID)
if authorized.Status == "failed" {
	log.Fatal(authorized.FailureReason) // the acquirer's own reason
}
captured, err := client.Payments.Capture(ctx, payment.ID)

check, err := client.Payments.Retrieve(ctx, payment.ID) // check.Status == "captured"
```

`NewClient("")` reads `PAYMENTFLOW_API_KEY` from the environment.

Runnable: `PAYMENTFLOW_API_KEY=sk_test_… go run ./examples/quickstart`.

## The services

`client.Payments`, `client.Refunds`, `client.Balance`, `client.BalanceTransactions`,
`client.Events`, `client.Analytics`, `client.Usage`, `client.RequestLogs`,
`client.WebhookEndpoints`, `client.WebhookDeliveries`, `client.TestHelpers`. Every method takes
`context.Context` first and is exactly one HTTP request — there is no `CreateAndCapture`,
because a method that makes two chargeable calls behind one name has failure modes a caller
cannot reason about.

Refunds are created through `client.Payments.Refund(ctx, id, params)`, which returns the
**`*Payment`** — the refund is the newest entry in its `Refunds` slice.

## Idempotency

Every payment mutation (`Create`, `Authorize`, `Capture`, `Refund`, `Void`) is sent with an
`Idempotency-Key`. The SDK generates one **once per call** and reuses it across every retry.
Supply your own when the retry must survive your *process* restarting:

```go
client.Payments.Create(ctx, params, paymentflow.WithIdempotencyKey(durableKey))
```

## Pagination

Iterating is the paginating thing:

```go
page, err := client.Payments.List(ctx, &paymentflow.ListPaymentsParams{Status: "captured"})
for payment, err := range page.All(ctx) {
	if err != nil { /* a fetch failed mid-iteration */ }
	// every captured payment, across every page
}
```

A `break` stops making requests there. `page.Next(ctx)` gives manual control. The M19 lists are
`*CursorPage[T]`; `/v1/webhook_deliveries` and `/v1/test/decisions` are `*OffsetPage[T]`, which
also reports a total.

## Errors

Branch with `errors.As`, not on the status code — a 409 is both a retryable
`*IdempotencyError` and a permanent `*InvalidRequestError`, and the platform tells them apart.

| type | when |
|---|---|
| `*AuthenticationError` | key missing, malformed, or unrecognised |
| `*PermissionError` | valid key, not allowed — wrong scope or mode |
| `*InvalidRequestError` | understood and rejected; `.Param` / `.FieldErrors` say where |
| `*IdempotencyError` | an `Idempotency-Key` conflict; may succeed later |
| `*RateLimitError` | rate limit or daily quota; `.RetryAfter` |
| `*ConnectionError` | no response — DNS, reset, client timeout; `errors.Unwrap` for the cause |
| `*APIError` | a 5xx, or a success this SDK could not read |

Every one carries `.RequestID` (quote it in support) and `.Attempts`. `paymentflow.AsError(err)`
returns the shared shape for handling any of them uniformly. Webhook verification failures are
`*WebhookSignatureError` / `*WebhookTimestampError` / `*WebhookPayloadError`.

## Retries

`GET`, `DELETE`, and any request with an `Idempotency-Key` are retried on `429` and transient
`5xx` (not `501`). `Retry-After` is honoured up to 60s; a longer one is not slept off inside
your handler — it is returned as a `*RateLimitError` with `.RetryAfter`. Backoff is full-jitter,
500ms doubling to 8s. The retry wait respects the caller's `context`. Default budget 3;
`WithMaxRetries(n)` on the client, `WithRequestMaxRetries(n)` per call.

## Webhooks

`ConstructEvent` is a package function — a receiver often holds no API key:

```go
body, _ := io.ReadAll(r.Body) // the RAW bytes, before anything decodes them
event, err := paymentflow.ConstructEvent(body, r.Header.Get(paymentflow.SignatureHeader), secret, 0)
// 0 tolerance means the 5-minute default
switch event.Type {
case "payment.captured": ...
default: // ignore unknown types
}
```

Verified against the same vectors the platform's own signer uses. Dedupe on `event.ID`. See
`examples/webhook-receiver`.

## Building this SDK

```bash
cd sdks/go
go build ./...
go vet ./...
go test ./...
gofmt -l .   # must print nothing
```

`parity_test.go` asserts `contract.go`, `operations.go`, `vocabularies.go` and `models.go`
against `../shared/fixtures/*.json` — the same fixtures the Node, Python and Java SDKs assert
against.
