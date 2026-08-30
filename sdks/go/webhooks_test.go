package paymentflow

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"
)

type sigVector struct {
	Name       string `json:"name"`
	Secret     string `json:"secret"`
	Timestamp  int64  `json:"timestamp"`
	Body       string `json:"body"`
	ExpectedV1 string `json:"expectedV1"`
}

func loadVectors(t *testing.T) []sigVector {
	t.Helper()
	path := filepath.Join("..", "..", "notification-service", "src", "test", "resources",
		"signature-vectors", "webhook-signature-vectors.json")
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read shared vectors: %v", err)
	}
	var doc struct {
		Vectors []sigVector `json:"vectors"`
	}
	if err := json.Unmarshal(raw, &doc); err != nil {
		t.Fatalf("parse vectors: %v", err)
	}
	if len(doc.Vectors) == 0 {
		t.Fatal("the shared vector file is empty")
	}
	return doc.Vectors
}

func TestEveryVectorProducesItsPublishedSignature(t *testing.T) {
	for _, v := range loadVectors(t) {
		if got := SignPayload(v.Secret, v.Timestamp, []byte(v.Body)); got != v.ExpectedV1 {
			t.Errorf("vector %s: got %s, want %s", v.Name, got, v.ExpectedV1)
		}
	}
}

func vectorNamed(t *testing.T, name string) sigVector {
	for _, v := range loadVectors(t) {
		if v.Name == name {
			return v
		}
	}
	t.Fatalf("no vector named %s", name)
	return sigVector{}
}

func TestConstructEventVerifiesAGenuineDeliveryAndReturnsItsEvent(t *testing.T) {
	v := vectorNamed(t, "realistic_payment_authorized")
	header := SignatureHeaderFor(v.Secret, v.Timestamp, []byte(v.Body))

	event, err := constructEventAt([]byte(v.Body), header, v.Secret, 5*time.Minute, time.Unix(v.Timestamp, 0))
	if err != nil {
		t.Fatalf("ConstructEvent: %v", err)
	}
	if event.ID != "evt_3f2504e04f8941d39a0c0305e82c3301" || event.Type != "payment.authorized" {
		t.Fatalf("wrong event: %+v", event)
	}
	if event.APIVersion != "2026-08-01" {
		t.Fatalf("apiVersion = %q", event.APIVersion)
	}
	if event.DataObject()["object"] != "payment" {
		t.Fatalf("data.object = %v", event.DataObject()["object"])
	}
}

func TestATamperedBodyIsAHostileSignatureFailure(t *testing.T) {
	v := loadVectors(t)[0]
	header := SignatureHeaderFor(v.Secret, v.Timestamp, []byte(v.Body))
	_, err := constructEventAt([]byte(v.Body+" "), header, v.Secret, 5*time.Minute, time.Unix(v.Timestamp, 0))
	var sigErr *WebhookSignatureError
	if !errors.As(err, &sigErr) {
		t.Fatalf("want *WebhookSignatureError, got %v", err)
	}
}

func TestAValidButStaleDeliveryIsATimestampFailure(t *testing.T) {
	v := vectorNamed(t, "realistic_payment_authorized")
	header := SignatureHeaderFor(v.Secret, v.Timestamp, []byte(v.Body))
	_, err := constructEventAt([]byte(v.Body), header, v.Secret, 300*time.Second, time.Unix(v.Timestamp+3600, 0))
	var tsErr *WebhookTimestampError
	if !errors.As(err, &tsErr) {
		t.Fatalf("want *WebhookTimestampError, got %v", err)
	}
	if tsErr.Timestamp != v.Timestamp || tsErr.SkewSeconds < 3600 {
		t.Fatalf("wrong detail: %+v", tsErr)
	}
}

func TestARotationWindowHeaderVerifiesIfEitherSignatureMatches(t *testing.T) {
	secret := "whsec_current"
	ts := int64(1785758400)
	body := []byte(`{"id":"evt_x","type":"payment.captured","data":{}}`)
	good := SignPayload(secret, ts, body)
	header := "t=" + itoa(ts) + ",v1=0000000000000000000000000000000000000000000000000000000000000000,v1=" + good

	event, err := constructEventAt(body, header, secret, 5*time.Minute, time.Unix(ts, 0))
	if err != nil {
		t.Fatalf("ConstructEvent: %v", err)
	}
	if event.ID != "evt_x" {
		t.Fatalf("wrong event: %+v", event)
	}
}

func TestAHeaderWithNoTimestampIsRejected(t *testing.T) {
	_, err := ConstructEvent(nil, "v1=abc", "whsec_x", time.Minute)
	var sigErr *WebhookSignatureError
	if !errors.As(err, &sigErr) {
		t.Fatalf("want *WebhookSignatureError, got %v", err)
	}
}

func itoa(v int64) string {
	b, _ := json.Marshal(v)
	return string(b)
}
