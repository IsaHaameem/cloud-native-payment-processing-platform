package paymentflow

import (
	"context"
	"encoding/json"
	"fmt"
)

// The plumbing every resource service shares. Services are thin on purpose: a method names an
// operation, hands over its parameters, and returns what the API returned. Everything that
// could differ between them — how a path is filled in, which query parameters are legal, when
// an idempotency key is generated, what gets retried — lives in the transport.

func decodeError(o operationDescriptor, err error) error {
	return &APIError{&errorImpl{Message: fmt.Sprintf("could not decode the %s response: %s", o.ID, err)}}
}

// send issues one call and decodes the JSON object body into *T.
func send[T any](ctx context.Context, t *transport, o operationDescriptor, pathParams map[string]string,
	query map[string]any, body any, opts []RequestOption) (*T, error) {
	res, err := t.do(ctx, o, pathParams, query, body, newRequestConfig(opts))
	if err != nil {
		return nil, err
	}
	out := new(T)
	if len(res.body) > 0 {
		if err := json.Unmarshal(res.body, out); err != nil {
			return nil, decodeError(o, err)
		}
	}
	return out, nil
}

// sendList decodes a bare JSON array response element-wise.
func sendList[T any](ctx context.Context, t *transport, o operationDescriptor, pathParams map[string]string,
	opts []RequestOption) ([]T, error) {
	res, err := t.do(ctx, o, pathParams, nil, nil, newRequestConfig(opts))
	if err != nil {
		return nil, err
	}
	var out []T
	if len(res.body) > 0 {
		if err := json.Unmarshal(res.body, &out); err != nil {
			return nil, decodeError(o, err)
		}
	}
	return out, nil
}

// sendNoContent issues a call whose response body is discarded (a 204).
func sendNoContent(ctx context.Context, t *transport, o operationDescriptor, pathParams map[string]string,
	opts []RequestOption) error {
	_, err := t.do(ctx, o, pathParams, nil, nil, newRequestConfig(opts))
	return err
}

// q is a small builder for a query map that drops zero values.
type q map[string]any

func (m q) str(key, value string) q {
	if value != "" {
		m[key] = value
	}
	return m
}

func (m q) int64p(key string, value *int64) q {
	if value != nil {
		m[key] = *value
	}
	return m
}

func (m q) intp(key string, value *int) q {
	if value != nil {
		m[key] = *value
	}
	return m
}

func (m q) strs(key string, value []string) q {
	if len(value) > 0 {
		m[key] = value
	}
	return m
}

func (m q) meta(key string, value map[string]string) q {
	if len(value) > 0 {
		m[key] = value
	}
	return m
}

// Int64 returns a pointer to v — a convenience for the optional *int64 filter fields.
func Int64(v int64) *int64 { return &v }

// Int returns a pointer to v.
func Int(v int) *int { return &v }
