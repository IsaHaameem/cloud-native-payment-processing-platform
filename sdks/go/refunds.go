package paymentflow

import "context"

// RefundsService is client.Refunds — reading refunds. They are created through Payments.Refund.
type RefundsService struct{ t *transport }

// ListRefundsParams filters Refunds.List. All fields optional.
type ListRefundsParams struct {
	Limit         *int64
	Payment       string
	Status        string
	CreatedAfter  string
	CreatedBefore string
	Metadata      map[string]string
}

func (p *ListRefundsParams) query() map[string]any {
	if p == nil {
		return nil
	}
	return q{}.int64p("limit", p.Limit).str("payment", p.Payment).str("status", p.Status).
		str("created_after", p.CreatedAfter).str("created_before", p.CreatedBefore).
		meta("metadata", p.Metadata)
}

// Retrieve reads one refund.
func (s *RefundsService) Retrieve(ctx context.Context, id string, opts ...RequestOption) (*Refund, error) {
	return send[Refund](ctx, s.t, op("getRefund"), map[string]string{"id": id}, nil, nil, opts)
}

// List lists your refunds, most recent first. The result paginates transparently.
func (s *RefundsService) List(ctx context.Context, params *ListRefundsParams, opts ...RequestOption) (*CursorPage[Refund], error) {
	return listCursor[Refund](ctx, s.t, op("listRefunds"), params.query(), opts)
}
