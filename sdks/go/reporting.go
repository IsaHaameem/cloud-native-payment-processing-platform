package paymentflow

import "context"

// AnalyticsService is client.Analytics — payment activity summarized over a window.
//
// The window is RFC 3339 instants here, and calendar dates for Usage — the platform's spelling,
// not this SDK's. Usage is metered per UTC day, so a window with a time in it would imply a
// precision the meter does not have.
type AnalyticsService struct{ t *transport }

// RetrievePaymentSummary summarizes payment activity over [from, to] (RFC 3339 instants), with
// hourly buckets. Empty strings mean "from the start of recorded history" / "to now".
func (s *AnalyticsService) RetrievePaymentSummary(ctx context.Context, from, to string, opts ...RequestOption) (*AnalyticsSummary, error) {
	return send[AnalyticsSummary](ctx, s.t, op("getPaymentAnalytics"), nil,
		q{}.str("from", from).str("to", to), nil, opts)
}

// UsageService is client.Usage — your API usage, metered per UTC day.
type UsageService struct{ t *transport }

// Retrieve reads usage over [from, to] as calendar dates (YYYY-MM-DD).
func (s *UsageService) Retrieve(ctx context.Context, from, to string, opts ...RequestOption) (*UsageSummary, error) {
	return send[UsageSummary](ctx, s.t, op("getUsage"), nil,
		q{}.str("from", from).str("to", to), nil, opts)
}

// RequestLogsService is client.RequestLogs — your API calls, as the platform recorded them.
// Each row is keyed by the RequestID this SDK reports on every response and every returned
// *Error, so a call you captured in your own logs can be looked up here directly.
type RequestLogsService struct{ t *transport }

// ListRequestLogsParams filters RequestLogs.List.
type ListRequestLogsParams struct {
	Limit         *int64
	CreatedAfter  string
	CreatedBefore string
	StatusCode    *int64
	Method        string
}

func (p *ListRequestLogsParams) query() map[string]any {
	if p == nil {
		return nil
	}
	return q{}.int64p("limit", p.Limit).
		str("created_after", p.CreatedAfter).str("created_before", p.CreatedBefore).
		int64p("status_code", p.StatusCode).str("method", p.Method)
}

// List lists your API calls, most recent first. The result paginates transparently.
func (s *RequestLogsService) List(ctx context.Context, params *ListRequestLogsParams, opts ...RequestOption) (*CursorPage[RequestLog], error) {
	return listCursor[RequestLog](ctx, s.t, op("listRequestLogs"), params.query(), opts)
}
