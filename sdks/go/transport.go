package paymentflow

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"io"
	"math"
	mrand "math/rand"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

const idempotencyHeader = "Idempotency-Key"

// Backoff constants. Milliseconds in Node, seconds in Python, and here — what must agree is the
// interval those spell, and it does: 500ms doubling to 8s, Retry-After honoured to 60s.
const (
	baseBackoff           = 500 * time.Millisecond
	maxBackoff            = 8 * time.Second
	maxHonouredRetryAfter = 60 * time.Second
)

// RateLimitMeta is the daily quota, as reported on a measured response. ResetSeconds is
// telemetry, not a retry hint — it describes the daily window even on a success.
type RateLimitMeta struct {
	Limit        *int64
	Remaining    *int64
	ResetSeconds *int64
}

// ResponseMeta is everything a caller can learn about the exchange beyond the body. It is
// attached to a page; for a single object, read RequestID off a returned *Error.
type ResponseMeta struct {
	StatusCode    int
	RequestID     string
	CorrelationID string
	APIVersion    string
	Deprecated    bool
	RateLimit     *RateLimitMeta
	Attempts      int
}

type transportResult struct {
	body []byte
	meta ResponseMeta
}

type transport struct {
	client *Client
}

// attempt outcome.
type attempt struct {
	result     *transportResult
	err        error
	retryable  bool
	retryAfter time.Duration // 0 = not provided
}

func (t *transport) do(ctx context.Context, o operationDescriptor, pathParams map[string]string,
	query map[string]any, body any, rc requestConfig) (*transportResult, error) {

	if ctx == nil {
		ctx = context.Background()
	}

	reqURL, err := t.buildURL(o, pathParams, query)
	if err != nil {
		return nil, err
	}
	headers, err := t.buildHeaders(o, rc)
	if err != nil {
		return nil, err
	}

	var payload []byte
	if o.HasRequestBody && body != nil {
		payload, err = json.Marshal(body)
		if err != nil {
			return nil, fmt.Errorf("paymentflow: could not encode the request body: %w", err)
		}
	}

	replayable := o.replayable()
	if _, ok := headers[idempotencyHeader]; ok {
		replayable = true
	}

	maxRetries := t.client.maxRetries
	if rc.maxRetries != nil {
		maxRetries = *rc.maxRetries
	}
	timeout := t.client.timeout
	if rc.timeout != nil {
		timeout = *rc.timeout
	}

	for n := 1; ; n++ {
		a := t.attempt(ctx, o.Method, reqURL, headers, payload, timeout, n)
		if a.err == nil {
			return a.result, nil
		}

		remaining := maxRetries - (n - 1)
		delay, retry := retryDelay(a, n)
		if remaining <= 0 || !replayable || !retry {
			return nil, a.err
		}
		select {
		case <-time.After(delay):
		case <-ctx.Done():
			return nil, connectionError("the retry wait was cancelled: "+ctx.Err().Error(), n, ctx.Err())
		}
	}
}

func (t *transport) attempt(ctx context.Context, method, reqURL string, headers map[string]string,
	payload []byte, timeout time.Duration, n int) attempt {

	attemptCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	var bodyReader io.Reader
	if payload != nil {
		bodyReader = bytes.NewReader(payload)
	}
	req, err := http.NewRequestWithContext(attemptCtx, method, reqURL, bodyReader)
	if err != nil {
		return attempt{err: connectionError("could not build the request: "+err.Error(), n, err)}
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}

	resp, err := t.client.httpClient.Do(req)
	if err != nil {
		msg := "the request could not be completed: " + err.Error()
		if attemptCtx.Err() == context.DeadlineExceeded && ctx.Err() == nil {
			msg = fmt.Sprintf("the request timed out after %s", timeout)
		}
		// A timeout or a dropped connection is retryable — the commonest transient failure
		// there is, and the idempotency key is what makes replaying one safe. A cancelled
		// parent context is not.
		return attempt{err: connectionError(msg, n, err), retryable: ctx.Err() == nil}
	}
	defer resp.Body.Close()

	return readResponse(resp, n)
}

func readResponse(resp *http.Response, n int) attempt {
	retryAfter := durationHeader(resp, "Retry-After")
	meta := ResponseMeta{
		StatusCode:    resp.StatusCode,
		RequestID:     resp.Header.Get("X-Request-Id"),
		CorrelationID: resp.Header.Get("X-Correlation-Id"),
		APIVersion:    resp.Header.Get("PaymentFlow-Version"),
		Deprecated:    resp.Header.Get("Deprecation") != "",
		RateLimit:     rateLimitMeta(resp),
		Attempts:      n,
	}

	var raw []byte
	if resp.StatusCode != http.StatusNoContent {
		raw, _ = io.ReadAll(resp.Body)
	}

	ok := resp.StatusCode >= 200 && resp.StatusCode < 300
	if ok {
		if len(raw) > 0 && !json.Valid(raw) {
			// 2xx with a body this SDK cannot read. Retrying is the right guess: the realistic
			// cause is an intermediary that truncated it.
			return attempt{
				err: &APIError{&errorImpl{
					StatusCode: resp.StatusCode, RequestID: meta.RequestID, CorrelationID: meta.CorrelationID,
					Attempts: n, Message: "The API returned a success status with a body that is not JSON.",
				}},
				retryable: true,
			}
		}
		return attempt{result: &transportResult{body: raw, meta: meta}}
	}

	var parsed map[string]any
	if len(raw) > 0 {
		_ = json.Unmarshal(raw, &parsed) // a non-object error body leaves parsed nil; that is fine
	}
	retryable := resp.StatusCode == 429 || (resp.StatusCode >= 500 && resp.StatusCode != 501)
	return attempt{
		err:        errorFromResponse(parsed, resp.StatusCode, meta.RequestID, meta.CorrelationID, retryAfter, n),
		retryable:  retryable,
		retryAfter: retryAfter,
	}
}

func (t *transport) buildURL(o operationDescriptor, pathParams map[string]string, query map[string]any) (string, error) {
	path := o.Path
	for {
		open := strings.IndexByte(path, '{')
		if open < 0 {
			break
		}
		closeIdx := strings.IndexByte(path[open:], '}') + open
		name := path[open+1 : closeIdx]
		value := pathParams[name]
		if value == "" {
			return "", fmt.Errorf("paymentflow: %q is required by %s and was not supplied", name, o.ID)
		}
		path = path[:open] + url.PathEscape(value) + path[closeIdx+1:]
	}

	values := url.Values{}
	for name, v := range query {
		if v == nil {
			continue
		}
		if !contains(o.QueryParameters, name) {
			return "", fmt.Errorf("paymentflow: %q is not a query parameter of %s; it accepts: %s",
				name, o.ID, strings.Join(o.QueryParameters, ", "))
		}
		switch tv := v.(type) {
		case []string:
			for _, e := range tv {
				values.Add(name, e)
			}
		case map[string]string:
			for k, e := range tv {
				values.Set(name+"["+k+"]", e)
			}
		default:
			values.Set(name, queryScalar(v))
		}
	}

	full := t.client.baseURL + path
	if enc := values.Encode(); enc != "" {
		full += "?" + enc
	}
	return full, nil
}

func (t *transport) buildHeaders(o operationDescriptor, rc requestConfig) (map[string]string, error) {
	h := map[string]string{
		"Authorization":       "Bearer " + t.client.apiKey,
		"Accept":              "application/json",
		"PaymentFlow-Version": t.client.apiVersion,
		"User-Agent":          userAgent,
	}
	if o.HasRequestBody {
		h["Content-Type"] = "application/json"
	}
	if rc.correlationID != "" {
		h["X-Correlation-Id"] = rc.correlationID
	}
	if contains(o.RequiredHeaders, idempotencyHeader) {
		if rc.idempotencyKey != "" {
			h[idempotencyHeader] = rc.idempotencyKey
		} else {
			key, err := uuidV4()
			if err != nil {
				return nil, fmt.Errorf("paymentflow: could not generate an idempotency key: %w", err)
			}
			h[idempotencyHeader] = key
		}
	} else if rc.idempotencyKey != "" {
		// The caller asked for one on an operation the contract does not require it for. Sent
		// rather than dropped: they know something about their own retry story that the
		// contract does not.
		h[idempotencyHeader] = rc.idempotencyKey
	}
	return h, nil
}

// retryDelay returns how long to wait before the next attempt, and whether to retry at all.
// Retry-After wins over anything computed, except an interval so long that waiting it out is
// indistinguishable from hanging.
func retryDelay(a attempt, n int) (time.Duration, bool) {
	if !a.retryable {
		return 0, false
	}
	if a.retryAfter > 0 {
		if a.retryAfter > maxHonouredRetryAfter {
			return 0, false
		}
		return a.retryAfter, true
	}
	// Full jitter: uniform over [0, ceiling). ceiling/2 + jitter reconverges a fleet recovering
	// from one outage into the wave that caused it.
	ceiling := time.Duration(math.Min(float64(maxBackoff), float64(baseBackoff)*math.Pow(2, float64(n-1))))
	return time.Duration(mrand.Int63n(int64(ceiling) + 1)), true
}

func rateLimitMeta(resp *http.Response) *RateLimitMeta {
	limit := intHeader(resp, "RateLimit-Limit")
	remaining := intHeader(resp, "RateLimit-Remaining")
	reset := intHeader(resp, "RateLimit-Reset")
	if limit == nil && remaining == nil && reset == nil {
		return nil
	}
	return &RateLimitMeta{Limit: limit, Remaining: remaining, ResetSeconds: reset}
}

func intHeader(resp *http.Response, name string) *int64 {
	raw := resp.Header.Get(name)
	if raw == "" {
		return nil
	}
	v, err := strconv.ParseInt(strings.TrimSpace(raw), 10, 64)
	if err != nil {
		return nil
	}
	return &v
}

func durationHeader(resp *http.Response, name string) time.Duration {
	raw := resp.Header.Get(name)
	if raw == "" {
		return 0
	}
	seconds, err := strconv.ParseFloat(strings.TrimSpace(raw), 64)
	if err != nil || seconds < 0 {
		return 0
	}
	return time.Duration(seconds * float64(time.Second))
}

func queryScalar(v any) string {
	switch tv := v.(type) {
	case bool:
		if tv {
			return "true"
		}
		return "false"
	case string:
		return tv
	default:
		return fmt.Sprintf("%v", tv)
	}
}

func contains(list []string, want string) bool {
	for _, s := range list {
		if s == want {
			return true
		}
	}
	return false
}

// uuidV4 builds a random UUID from crypto/rand — no dependency for one string.
func uuidV4() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16]), nil
}
