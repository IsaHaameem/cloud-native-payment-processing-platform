// Command quickstart runs the first-payment flow against a PaymentFlow test key.
//
//	PAYMENTFLOW_API_KEY=sk_test_... go run ./examples/quickstart
package main

import (
	"context"
	"fmt"
	"log"
	"os"

	paymentflow "github.com/IsaHaameem/cloud-native-payment-processing-platform/sdks/go"
)

func main() {
	client, err := paymentflow.NewClient(os.Getenv("PAYMENTFLOW_API_KEY"))
	if err != nil {
		log.Fatal(err)
	}
	ctx := context.Background()

	payment, err := client.Payments.Create(ctx, paymentflow.CreatePaymentParams{
		AmountMinor:        1000,
		Currency:           "USD",
		Description:        "Order A-1234",
		PaymentMethodToken: "pm_card_visa",
	})
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("created", payment.ID, payment.Status)

	if _, err := client.Payments.Authorize(ctx, payment.ID); err != nil {
		log.Fatal(err)
	}
	captured, err := client.Payments.Capture(ctx, payment.ID)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("captured", captured.Status, captured.CapturedAmountMinor)

	check, err := client.Payments.Retrieve(ctx, payment.ID)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("verified", check.Status) // -> "captured"
}
