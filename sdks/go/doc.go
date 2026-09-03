// Package paymentflow is the official PaymentFlow API client for Go.
//
// It is one dependency-free package over the public /v1 API: typed models, a typed error
// hierarchy you branch on with errors.As, automatic idempotency keys that survive a retry,
// backoff that reads Retry-After instead of guessing, transparent pagination, and webhook
// signature verification.
//
// # First payment
//
//	client, err := paymentflow.NewClient(os.Getenv("PAYMENTFLOW_API_KEY"))
//	if err != nil {
//		log.Fatal(err)
//	}
//
//	ctx := context.Background()
//	payment, err := client.Payments.Create(ctx, paymentflow.CreatePaymentParams{
//		AmountMinor:        1000, // integer minor units: 1000 = 10.00
//		Currency:           "USD",
//		Description:        "Order A-1234",
//		PaymentMethodToken: "pm_card_visa",
//	})
//
//	authorized, err := client.Payments.Authorize(ctx, payment.ID)
//	captured, err := client.Payments.Capture(ctx, payment.ID)
//	check, err := client.Payments.Retrieve(ctx, payment.ID) // check.Status == "captured"
//
// # Contract fidelity
//
// The generated-equivalent layer (contract.go, operations.go, vocabularies.go, models.go) is
// hand-written and asserted against ../shared/fixtures/*.json — the same language-neutral
// golden fixtures the Node, Python and Java SDKs assert against — by parity_test.go.
//
// The SDK targets API revision 2026-08-01. Its own version moves on a separate schedule; see
// Version.
package paymentflow
