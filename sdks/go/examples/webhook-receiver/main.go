// Command webhook-receiver is a minimal HTTP endpoint that verifies PaymentFlow deliveries.
//
//	PAYMENTFLOW_WEBHOOK_SECRET=whsec_... go run ./examples/webhook-receiver
package main

import (
	"io"
	"log"
	"net/http"
	"os"

	paymentflow "github.com/IsaHaameem/cloud-native-payment-processing-platform/sdks/go"
)

func main() {
	secret := os.Getenv("PAYMENTFLOW_WEBHOOK_SECRET")

	http.HandleFunc("/webhooks/paymentflow", func(w http.ResponseWriter, r *http.Request) {
		// The RAW bytes, read before anything decodes them — the signature covers what was sent.
		body, err := io.ReadAll(r.Body)
		if err != nil {
			http.Error(w, "cannot read body", http.StatusBadRequest)
			return
		}

		event, err := paymentflow.ConstructEvent(body, r.Header.Get(paymentflow.SignatureHeader), secret, 0)
		if err != nil {
			// Did not come from PaymentFlow, or did not arrive intact.
			http.Error(w, "unverified", http.StatusBadRequest)
			return
		}

		switch event.Type {
		case "payment.captured":
			log.Println("captured:", event.DataObject()["id"])
		case "payment.failed":
			log.Println("failed:", event.DataObject()["failureReason"])
		default:
			// Ignore what you do not recognise — new types ship without a new API revision.
		}
		// Dedupe on event.ID — stable across retries and replays.
		w.WriteHeader(http.StatusNoContent)
	})

	log.Fatal(http.ListenAndServe(":8080", nil))
}
