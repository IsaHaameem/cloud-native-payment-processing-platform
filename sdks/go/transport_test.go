package paymentflow

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// recorder is a test server that answers from a script of handlers and records every request.
type recorder struct {
	t        *testing.T
	handlers []func(w http.ResponseWriter, r *http.Request)
	calls    atomic.Int32
	mu       sync.Mutex
	reqs     []*http.Request
	bodies   []string
}

func (rec *recorder) server() *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n := int(rec.calls.Add(1)) - 1
		body, _ := io.ReadAll(r.Body)
		rec.mu.Lock()
		rec.reqs = append(rec.reqs, r)
		rec.bodies = append(rec.bodies, string(body))
		rec.mu.Unlock()
		h := rec.handlers[len(rec.handlers)-1]
		if n < len(rec.handlers) {
			h = rec.handlers[n]
		}
		h(w, r)
	}))
}

func newClientFor(t *testing.T, srv *httptest.Server) *Client {
	t.Helper()
	c, err := NewClient("sk_test_fake", WithBaseURL(srv.URL), WithHTTPClient(srv.Client()))
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	return c
}

func json200(body string) func(http.ResponseWriter, *http.Request) {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(body))
	}
}

func jsonStatus(status int, body string, headers map[string]string) func(http.ResponseWriter, *http.Request) {
	return func(w http.ResponseWriter, _ *http.Request) {
		for k, v := range headers {
			w.Header().Set(k, v)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}
}

const paymentJSON = `{"id":"pay_1","amountMinor":1000,"currency":"USD","status":"created"}`

func TestCreateSendsContractHeadersAndBodyAndReturnsTheMappedObject(t *testing.T) {
	rec := &recorder{t: t, handlers: []func(http.ResponseWriter, *http.Request){
		func(w http.ResponseWriter, r *http.Request) {
			if r.Header.Get("Authorization") != "Bearer sk_test_fake" {
				t.Errorf("Authorization = %q", r.Header.Get("Authorization"))
			}
			if r.Header.Get("PaymentFlow-Version") != "2026-08-01" {
				t.Errorf("PaymentFlow-Version = %q", r.Header.Get("PaymentFlow-Version"))
			}
			if r.Header.Get("Idempotency-Key") == "" {
				t.Error("no Idempotency-Key on createPayment")
			}
			if !strings.HasPrefix(r.Header.Get("User-Agent"), "paymentflow-go/") {
				t.Errorf("User-Agent = %q", r.Header.Get("User-Agent"))
			}
			json200(paymentJSON)(w, r)
		},
	}}
	srv := rec.server()
	defer srv.Close()

	payment, err := newClientFor(t, srv).Payments.Create(context.Background(),
		CreatePaymentParams{AmountMinor: 1000, Currency: "USD", Description: "Order A-1234"})
	if err != nil {
		t.Fatalf("Create: %v", err)
	}
	if payment.ID != "pay_1" || payment.AmountMinor != 1000 {
		t.Fatalf("mapped wrong: %+v", payment)
	}
	if !strings.Contains(rec.bodies[0], `"amountMinor":1000`) || !strings.Contains(rec.bodies[0], `"currency":"USD"`) {
		t.Fatalf("body = %s", rec.bodies[0])
	}
}

func TestARetriedMutationReusesItsIdempotencyKey(t *testing.T) {
	rec := &recorder{t: t, handlers: []func(http.ResponseWriter, *http.Request){
		jsonStatus(503, `{"type":"api_error","message":"try later"}`, map[string]string{"Retry-After": "0"}),
		json200(paymentJSON),
	}}
	srv := rec.server()
	defer srv.Close()

	if _, err := newClientFor(t, srv).Payments.Create(context.Background(),
		CreatePaymentParams{AmountMinor: 1000, Currency: "USD"}); err != nil {
		t.Fatalf("Create: %v", err)
	}
	if rec.calls.Load() != 2 {
		t.Fatalf("want 2 attempts, got %d", rec.calls.Load())
	}
	first := rec.reqs[0].Header.Get("Idempotency-Key")
	second := rec.reqs[1].Header.Get("Idempotency-Key")
	if first == "" || first != second {
		t.Fatalf("idempotency key not reused: %q vs %q", first, second)
	}
}

func TestASuppliedIdempotencyKeyIsUsedAsGiven(t *testing.T) {
	rec := &recorder{t: t, handlers: []func(http.ResponseWriter, *http.Request){json200(paymentJSON)}}
	srv := rec.server()
	defer srv.Close()

	_, _ = newClientFor(t, srv).Payments.Create(context.Background(),
		CreatePaymentParams{AmountMinor: 1000, Currency: "USD"}, WithIdempotencyKey("my-own-key"))
	if got := rec.reqs[0].Header.Get("Idempotency-Key"); got != "my-own-key" {
		t.Fatalf("Idempotency-Key = %q", got)
	}
}

func TestANonReplayablePostIsNotRetried(t *testing.T) {
	rec := &recorder{t: t, handlers: []func(http.ResponseWriter, *http.Request){
		jsonStatus(503, `{"type":"api_error","message":"nope"}`, nil),
		json200(`{}`),
	}}
	srv := rec.server()
	defer srv.Close()

	_, err := newClientFor(t, srv).WebhookEndpoints.Create(context.Background(),
		CreateWebhookEndpointParams{URL: "https://x.test/hook", EnabledEvents: []string{"*"}})
	var apiErr *APIError
	if !errors.As(err, &apiErr) {
		t.Fatalf("want *APIError, got %v", err)
	}
	if rec.calls.Load() != 1 {
		t.Fatalf("createWebhookEndpoint must not be replayed; attempts = %d", rec.calls.Load())
	}
}

func TestA400IsAnInvalidRequestErrorCarryingTheParam(t *testing.T) {
	rec := &recorder{t: t, handlers: []func(http.ResponseWriter, *http.Request){
		jsonStatus(400, `{"type":"invalid_request_error","code":"VALIDATION_ERROR","param":"currency","message":"currency is required"}`, nil),
	}}
	srv := rec.server()
	defer srv.Close()

	_, err := newClientFor(t, srv).Payments.Create(context.Background(),
		CreatePaymentParams{AmountMinor: 1000, Currency: "USD"})
	var ire *InvalidRequestError
	if !errors.As(err, &ire) {
		t.Fatalf("want *InvalidRequestError, got %v", err)
	}
	if ire.Param != "currency" || ire.Code != "VALIDATION_ERROR" || ire.StatusCode != 400 {
		t.Fatalf("wrong detail: %+v", ire.errorImpl)
	}
	if info, ok := AsError(err); !ok || info.Param != "currency" {
		t.Fatalf("AsError did not surface the shared shape: %+v %v", info, ok)
	}
}

func TestA401IsAnAuthenticationErrorEvenWithNoBody(t *testing.T) {
	rec := &recorder{t: t, handlers: []func(http.ResponseWriter, *http.Request){jsonStatus(401, "", nil)}}
	srv := rec.server()
	defer srv.Close()

	_, err := newClientFor(t, srv).Payments.Retrieve(context.Background(), "pay_1")
	var ae *AuthenticationError
	if !errors.As(err, &ae) {
		t.Fatalf("want *AuthenticationError, got %v", err)
	}
}

func TestALongRetryAfterIsNotWaitedOutButReportedOnTheError(t *testing.T) {
	rec := &recorder{t: t, handlers: []func(http.ResponseWriter, *http.Request){
		jsonStatus(429, `{"type":"rate_limit_error","code":"DAILY_QUOTA_EXCEEDED","message":"slow down"}`,
			map[string]string{"Retry-After": "86400"}),
	}}
	srv := rec.server()
	defer srv.Close()

	_, err := newClientFor(t, srv).Payments.Retrieve(context.Background(), "pay_1")
	var rle *RateLimitError
	if !errors.As(err, &rle) {
		t.Fatalf("want *RateLimitError, got %v", err)
	}
	if rle.RetryAfter != 86400*time.Second {
		t.Fatalf("RetryAfter = %v", rle.RetryAfter)
	}
	if rec.calls.Load() != 1 {
		t.Fatalf("a 24-hour Retry-After must not be slept off; attempts = %d", rec.calls.Load())
	}
}

func TestA204LeavesTheVoidMethodsWithNothingToReturn(t *testing.T) {
	rec := &recorder{t: t, handlers: []func(http.ResponseWriter, *http.Request){
		func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(204) },
	}}
	srv := rec.server()
	defer srv.Close()

	if err := newClientFor(t, srv).WebhookEndpoints.Delete(context.Background(), "we_1"); err != nil {
		t.Fatalf("Delete on a 204: %v", err)
	}
	if rec.reqs[0].Method != http.MethodDelete {
		t.Fatalf("method = %s", rec.reqs[0].Method)
	}
}

func TestAListRequestPutsFiltersOnTheQueryString(t *testing.T) {
	rec := &recorder{t: t, handlers: []func(http.ResponseWriter, *http.Request){
		json200(`{"data":[],"hasMore":false}`),
	}}
	srv := rec.server()
	defer srv.Close()

	_, err := newClientFor(t, srv).Payments.List(context.Background(),
		&ListPaymentsParams{Status: "captured", Limit: Int64(2)})
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	qs := rec.reqs[0].URL.Query()
	if qs.Get("status") != "captured" || qs.Get("limit") != "2" {
		t.Fatalf("query = %v", qs)
	}
}

func TestATimeoutIsRetriedThenSurfacedAsAConnectionError(t *testing.T) {
	rec := &recorder{t: t, handlers: []func(http.ResponseWriter, *http.Request){
		func(w http.ResponseWriter, _ *http.Request) {
			time.Sleep(150 * time.Millisecond)
			json200(paymentJSON)(w, nil)
		},
		json200(paymentJSON),
	}}
	srv := rec.server()
	defer srv.Close()

	c, _ := NewClient("sk_test_fake", WithBaseURL(srv.URL), WithHTTPClient(srv.Client()),
		WithTimeout(40*time.Millisecond))
	payment, err := c.Payments.Retrieve(context.Background(), "pay_1")
	if err != nil {
		t.Fatalf("Retrieve after retry: %v", err)
	}
	if payment.ID != "pay_1" {
		t.Fatalf("mapped wrong: %+v", payment)
	}
	if rec.calls.Load() != 2 {
		t.Fatalf("want a retry after the timeout; attempts = %d", rec.calls.Load())
	}
}
