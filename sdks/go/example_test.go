package paymentflow_test

import (
	"context"
	"errors"
	"fmt"
	"log"
	"os"
	"time"

	paymentflow "github.com/IsaHaameem/cloud-native-payment-processing-platform/sdks/go"
)

// The first payment: create, authorize, capture, verify.
func ExampleClient_firstPayment() {
	client, err := paymentflow.NewClient(os.Getenv("PAYMENTFLOW_API_KEY"))
	if err != nil {
		log.Fatal(err)
	}
	ctx := context.Background()

	payment, err := client.Payments.Create(ctx, paymentflow.CreatePaymentParams{
		AmountMinor:        1000, // integer minor units: 1000 = 10.00
		Currency:           "USD",
		Description:        "Order A-1234",
		PaymentMethodToken: "pm_card_visa", // a test card that approves
	})
	if err != nil {
		log.Fatal(err)
	}

	authorized, err := client.Payments.Authorize(ctx, payment.ID)
	if err != nil {
		log.Fatal(err)
	}
	if authorized.Status == "failed" {
		log.Fatal(authorized.FailureReason) // the acquirer's own reason
	}

	captured, err := client.Payments.Capture(ctx, payment.ID)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println(captured.Status, captured.CapturedAmountMinor)
}

// Iterating a list is already the paginating thing — All crosses page boundaries.
func ExampleCursorPage_All() {
	client, _ := paymentflow.NewClient(os.Getenv("PAYMENTFLOW_API_KEY"))
	ctx := context.Background()

	page, err := client.Payments.List(ctx, &paymentflow.ListPaymentsParams{Status: "captured"})
	if err != nil {
		log.Fatal(err)
	}
	for payment, err := range page.All(ctx) {
		if err != nil {
			log.Fatal(err)
		}
		fmt.Println(payment.ID)
	}
}

// Branch on the error type with errors.As, not on the status code.
func ExampleAsError() {
	client, _ := paymentflow.NewClient(os.Getenv("PAYMENTFLOW_API_KEY"))
	_, err := client.Payments.Create(context.Background(), paymentflow.CreatePaymentParams{
		AmountMinor: 1000, Currency: "US",
	})

	var invalid *paymentflow.InvalidRequestError
	var rateLimited *paymentflow.RateLimitError
	var conn *paymentflow.ConnectionError
	switch {
	case errors.As(err, &invalid):
		fmt.Println("fix the request:", invalid.Param)
	case errors.As(err, &rateLimited):
		fmt.Println("retry after", rateLimited.RetryAfter)
	case errors.As(err, &conn):
		fmt.Println("network:", conn.Unwrap())
	case err != nil:
		if info, ok := paymentflow.AsError(err); ok {
			fmt.Println("request", info.RequestID, "failed:", info.Message)
		}
	}
}

// A per-call idempotency key that survives the process restarting, and a per-call timeout.
func ExampleClient_requestOptions() {
	client, _ := paymentflow.NewClient(os.Getenv("PAYMENTFLOW_API_KEY"))
	_, _ = client.Payments.Create(context.Background(),
		paymentflow.CreatePaymentParams{AmountMinor: 1000, Currency: "USD"},
		paymentflow.WithIdempotencyKey("order-4821-charge"),
		paymentflow.WithRequestTimeout(10*time.Second),
	)
}

// A webhook receiver verifies the raw bytes before trusting anything, and needs no API key.
func ExampleConstructEvent() {
	rawBody := []byte(`{"id":"evt_x","type":"payment.captured","data":{"object":{"id":"pay_1"}}}`)
	signatureHeader := "" // r.Header.Get(paymentflow.SignatureHeader)
	secret := os.Getenv("PAYMENTFLOW_WEBHOOK_SECRET")

	event, err := paymentflow.ConstructEvent(rawBody, signatureHeader, secret, 0)
	if err != nil {
		// Did not come from PaymentFlow, or did not arrive intact. Respond 400 and stop.
		return
	}
	switch event.Type {
	case "payment.captured":
		fmt.Println("captured:", event.DataObject()["id"])
	default:
		// Ignore what you do not recognise — new types ship without a new API revision.
	}
	// Dedupe on event.ID — it is stable across retries and replays.
}
