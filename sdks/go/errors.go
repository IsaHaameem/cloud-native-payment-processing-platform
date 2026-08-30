package paymentflow

import (
	"errors"
	"fmt"
	"time"
)

// errorImpl carries what the transport learned about a failed call. Every field is a zero value
// when the platform did not provide it — a 502 from a load balancer that never reached the
// platform has no Type, no body, and often no JSON. It is embedded (unexported) in every
// concrete error type below, so those fields promote onto each of them.
type errorImpl struct {
	StatusCode    int
	Type          string
	Code          string
	Param         string
	FieldErrors   []FieldError
	RequestID     string
	CorrelationID string
	DocURL        string
	Attempts      int
	Message       string
	// RetryAfter is set on a *RateLimitError: the platform's own answer to "when may I try
	// again". Zero when the response did not say.
	RetryAfter time.Duration

	wrapped error
}

func (e *errorImpl) Error() string {
	if e.RequestID != "" {
		return fmt.Sprintf("paymentflow: %s (request %s)", e.Message, e.RequestID)
	}
	return "paymentflow: " + e.Message
}

// Unwrap exposes the underlying transport failure on a *ConnectionError.
func (e *errorImpl) Unwrap() error { return e.wrapped }

func (e *errorImpl) apiError() *errorImpl { return e }

// Error is the shared shape of every API error this SDK returns. Branch with errors.As on the
// concrete types below — a 409 is both a retryable *IdempotencyError (a concurrent request
// holding the same key) and a permanent *InvalidRequestError (a state the resource cannot move
// from), and the platform tells them apart even though the status does not. To handle every
// kind uniformly, use AsError.
type Error struct{ *errorImpl }

// AsError reports whether err is (or wraps) any PaymentFlow API error and returns its shared
// shape. A ConnectionError, an AuthenticationError and the rest all satisfy it.
func AsError(err error) (*Error, bool) {
	var carrier interface{ apiError() *errorImpl }
	if errors.As(err, &carrier) {
		return &Error{carrier.apiError()}, true
	}
	return nil, false
}

// The concrete error types. Each embeds *errorImpl, so it satisfies the error interface, its
// fields promote, and errors.As finds it: errors.As(err, new(*paymentflow.RateLimitError)).

// AuthenticationError: the API key is missing, malformed, or not recognised. Retrying will not
// help.
type AuthenticationError struct{ *errorImpl }

// PermissionError: the key is valid but not allowed to do this — a missing scope, or the wrong
// mode.
type PermissionError struct{ *errorImpl }

// InvalidRequestError: the request was understood and rejected. Param and FieldErrors say where.
type InvalidRequestError struct{ *errorImpl }

// IdempotencyError: an Idempotency-Key conflict, most often a concurrent request holding the
// same key. May succeed on a later attempt.
type IdempotencyError struct{ *errorImpl }

// RateLimitError: the rate limit or daily quota was exceeded. RetryAfter carries the wait when
// the caller has exhausted the retry budget and wants to schedule the work.
type RateLimitError struct{ *errorImpl }

// ConnectionError: the request never produced a response — DNS, a reset connection, the
// client-side timeout. Unwrap returns the cause. There is no StatusCode, which is also why
// "did it happen?" is genuinely unknown here and the idempotency key matters most.
type ConnectionError struct{ *errorImpl }

// APIError: the platform failed to handle a request it accepted — a 5xx, or a success this SDK
// could not read. Not the caller's fault; report it with RequestID.
type APIError struct{ *errorImpl }

// errorFromResponse builds the error for a response the platform refused. body is whatever came
// back — a well-formed error envelope, JSON of some other shape, or nil — and none of those may
// panic from here.
func errorFromResponse(body map[string]any, statusCode int, requestIDHeader, correlationIDHeader string,
	retryAfter time.Duration, attempts int) error {

	str := func(key string) string {
		if v, ok := body[key].(string); ok {
			return v
		}
		return ""
	}

	typ := str("type")
	message := str("message")
	if message == "" {
		message = fmt.Sprintf("The API returned HTTP %d with no error message.", statusCode)
	}

	base := &errorImpl{
		StatusCode:    statusCode,
		Type:          typ,
		Code:          str("code"),
		Param:         str("param"),
		FieldErrors:   fieldErrors(body["errors"]),
		RequestID:     coalesce(str("requestId"), requestIDHeader),
		CorrelationID: coalesce(str("correlationId"), correlationIDHeader),
		DocURL:        str("docUrl"),
		Attempts:      attempts,
		Message:       message,
		RetryAfter:    retryAfter,
	}

	switch typ {
	case "authentication_error":
		return &AuthenticationError{base}
	case "permission_error":
		return &PermissionError{base}
	case "invalid_request_error":
		return &InvalidRequestError{base}
	case "idempotency_error":
		return &IdempotencyError{base}
	case "rate_limit_error":
		return &RateLimitError{base}
	case "api_error":
		return &APIError{base}
	}
	// An unrecognised type falls back to the status rather than failing: §9 lets new error
	// types ship without a new API revision.
	return byStatus(statusCode, base)
}

func byStatus(status int, base *errorImpl) error {
	switch {
	case status == 401:
		return &AuthenticationError{base}
	case status == 403:
		return &PermissionError{base}
	case status == 429:
		return &RateLimitError{base}
	case status >= 400 && status < 500:
		return &InvalidRequestError{base}
	default:
		return &APIError{base}
	}
}

func fieldErrors(raw any) []FieldError {
	list, ok := raw.([]any)
	if !ok {
		return nil
	}
	var out []FieldError
	for _, elem := range list {
		m, ok := elem.(map[string]any)
		if !ok {
			continue
		}
		fe := FieldError{}
		if v, ok := m["field"].(string); ok {
			fe.Field = v
		}
		if v, ok := m["message"].(string); ok {
			fe.Message = v
		}
		out = append(out, fe)
	}
	return out
}

func coalesce(a, b string) string {
	if a != "" {
		return a
	}
	return b
}

// connectionError wraps a transport failure with no HTTP response.
func connectionError(message string, attempts int, cause error) error {
	return &ConnectionError{&errorImpl{Message: message, Attempts: attempts, wrapped: cause}}
}
