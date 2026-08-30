package paymentflow

import "context"

// BalanceService is client.Balance — your current balance, per currency.
type BalanceService struct{ t *transport }

// Retrieve reads your balance.
func (s *BalanceService) Retrieve(ctx context.Context, opts ...RequestOption) (*Balance, error) {
	return send[Balance](ctx, s.t, op("getBalance"), nil, nil, nil, opts)
}

// BalanceTransactionsService is client.BalanceTransactions — the entries that moved your balance.
type BalanceTransactionsService struct{ t *transport }

// ListBalanceTransactionsParams filters BalanceTransactions.List.
type ListBalanceTransactionsParams struct {
	Limit         *int64
	CreatedAfter  string
	CreatedBefore string
}

func (p *ListBalanceTransactionsParams) query() map[string]any {
	if p == nil {
		return nil
	}
	return q{}.int64p("limit", p.Limit).
		str("created_after", p.CreatedAfter).str("created_before", p.CreatedBefore)
}

// List lists balance transactions, most recent first. The result paginates transparently.
func (s *BalanceTransactionsService) List(ctx context.Context, params *ListBalanceTransactionsParams, opts ...RequestOption) (*CursorPage[BalanceTransaction], error) {
	return listCursor[BalanceTransaction](ctx, s.t, op("listBalanceTransactions"), params.query(), opts)
}
