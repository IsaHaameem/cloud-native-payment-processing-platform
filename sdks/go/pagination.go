package paymentflow

import (
	"context"
	"encoding/json"
	"iter"
)

// CursorPage is one page of a cursor-paginated list — the M19 list shape. It reports no total
// count, deliberately: counting would cost a second full query on every request, and HasMore is
// the only question a paginating client needs.
//
// Iterating is the paginating thing:
//
//	for payment, err := range page.All(ctx) {
//		if err != nil { ... }
//	}
//
// A break stops making requests there. Next(ctx) gives a caller keeping their own cursor manual
// control.
type CursorPage[T any] struct {
	Data       []T
	HasMore    bool
	NextCursor string
	Meta       ResponseMeta

	fetch func(ctx context.Context, cursor string) (*CursorPage[T], error)
}

// Next fetches the following page, or returns (nil, nil) when this is the last.
func (p *CursorPage[T]) Next(ctx context.Context) (*CursorPage[T], error) {
	if !p.HasMore || p.NextCursor == "" {
		return nil, nil
	}
	return p.fetch(ctx, p.NextCursor)
}

// All yields every object from this page onward, fetching as it goes. On a fetch failure it
// yields the zero value with a non-nil error once, then stops.
func (p *CursorPage[T]) All(ctx context.Context) iter.Seq2[T, error] {
	return func(yield func(T, error) bool) {
		page := p
		for page != nil {
			for i := range page.Data {
				if !yield(page.Data[i], nil) {
					return
				}
			}
			next, err := page.Next(ctx)
			if err != nil {
				var zero T
				yield(zero, err)
				return
			}
			page = next
		}
	}
}

// OffsetPage is one page of an offset-paginated list — the older PageResponse shape, on the two
// endpoints D139 left alone (/v1/webhook_deliveries and /v1/test/decisions). Unlike CursorPage
// it does report a total.
type OffsetPage[T any] struct {
	Content       []T
	Page          int
	Size          int
	TotalElements int64
	TotalPages    int
	HasMore       bool
	Meta          ResponseMeta

	fetch func(ctx context.Context, page int) (*OffsetPage[T], error)
}

// Next fetches the following page, or returns (nil, nil) when this is the last.
func (p *OffsetPage[T]) Next(ctx context.Context) (*OffsetPage[T], error) {
	if !p.HasMore {
		return nil, nil
	}
	return p.fetch(ctx, p.Page+1)
}

// All yields every object from this page onward, fetching as it goes.
func (p *OffsetPage[T]) All(ctx context.Context) iter.Seq2[T, error] {
	return func(yield func(T, error) bool) {
		page := p
		for page != nil {
			for i := range page.Content {
				if !yield(page.Content[i], nil) {
					return
				}
			}
			next, err := page.Next(ctx)
			if err != nil {
				var zero T
				yield(zero, err)
				return
			}
			page = next
		}
	}
}

// ── envelopes ──────────────────────────────────────────────────────────────────────────────

type rawCursorEnvelope[T any] struct {
	Data       []T    `json:"data"`
	HasMore    *bool  `json:"hasMore"`
	NextCursor string `json:"nextCursor"`
}

type rawOffsetEnvelope[T any] struct {
	Content       []T    `json:"content"`
	Page          int    `json:"page"`
	Size          *int   `json:"size"`
	TotalElements *int64 `json:"totalElements"`
	TotalPages    int    `json:"totalPages"`
	Last          *bool  `json:"last"`
}

// listCursor issues the first request and returns a page that knows how to fetch the rest with
// the same filters — re-issuing a page request with different filters than the cursor was
// minted under is the classic way an auto-paginating client returns a result set that never
// existed.
func listCursor[T any](ctx context.Context, t *transport, o operationDescriptor,
	query map[string]any, opts []RequestOption) (*CursorPage[T], error) {

	rc := newRequestConfig(opts)
	var fetch func(ctx context.Context, cursor string) (*CursorPage[T], error)
	fetch = func(ctx context.Context, cursor string) (*CursorPage[T], error) {
		q := cloneQuery(query)
		if cursor != "" {
			q["starting_after"] = cursor
		}
		res, err := t.do(ctx, o, nil, q, nil, rc)
		if err != nil {
			return nil, err
		}
		var env rawCursorEnvelope[T]
		if len(res.body) > 0 {
			if err := json.Unmarshal(res.body, &env); err != nil {
				return nil, decodeError(o, err)
			}
		}
		hasMore := env.NextCursor != ""
		if env.HasMore != nil {
			hasMore = *env.HasMore
		}
		return &CursorPage[T]{
			Data:       env.Data,
			HasMore:    hasMore,
			NextCursor: env.NextCursor,
			Meta:       res.meta,
			fetch:      fetch,
		}, nil
	}
	return fetch(ctx, "")
}

func listOffset[T any](ctx context.Context, t *transport, o operationDescriptor, pathParams map[string]string,
	query map[string]any, opts []RequestOption) (*OffsetPage[T], error) {

	rc := newRequestConfig(opts)
	var fetch func(ctx context.Context, page int) (*OffsetPage[T], error)
	fetch = func(ctx context.Context, page int) (*OffsetPage[T], error) {
		q := cloneQuery(query)
		q["page"] = page
		res, err := t.do(ctx, o, pathParams, q, nil, rc)
		if err != nil {
			return nil, err
		}
		var env rawOffsetEnvelope[T]
		if len(res.body) > 0 {
			if err := json.Unmarshal(res.body, &env); err != nil {
				return nil, decodeError(o, err)
			}
		}
		size := len(env.Content)
		if env.Size != nil {
			size = *env.Size
		}
		total := int64(len(env.Content))
		if env.TotalElements != nil {
			total = *env.TotalElements
		}
		hasMore := env.Page+1 < env.TotalPages
		if env.Last != nil {
			hasMore = !*env.Last
		}
		return &OffsetPage[T]{
			Content:       env.Content,
			Page:          env.Page,
			Size:          size,
			TotalElements: total,
			TotalPages:    env.TotalPages,
			HasMore:       hasMore,
			Meta:          res.meta,
			fetch:         fetch,
		}, nil
	}
	return fetch(ctx, 0)
}

func cloneQuery(q map[string]any) map[string]any {
	out := make(map[string]any, len(q)+1)
	for k, v := range q {
		out[k] = v
	}
	return out
}
