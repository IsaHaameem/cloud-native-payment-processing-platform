package paymentflow

import "context"

// PaymentsService is client.Payments — the payment lifecycle. Seven methods, one per published
// operation, and no eighth: there is no CreateAndCapture convenience, because a method that
// makes two chargeable calls behind one name has failure modes a caller cannot reason about.
type PaymentsService struct{ t *transport }

// CreatePaymentParams is the body of Payments.Create. AmountMinor and Currency are required;
// AmountMinor is an integer in the currency's minor unit (1000 in USD is $10.00). The platform
// rejects a body without a positive AmountMinor every time, even though the published schema
// does not list it under required (D170).
type CreatePaymentParams struct {
	AmountMinor        int64             `json:"amountMinor"`
	Currency           string            `json:"currency"`
	Description        string            `json:"description,omitempty"`
	PaymentMethodToken string            `json:"paymentMethodToken,omitempty"`
	Metadata           map[string]string `json:"metadata,omitempty"`
}

// ListPaymentsParams filters Payments.List. All fields optional; a nil *ListPaymentsParams
// lists everything.
type ListPaymentsParams struct {
	Limit         *int64
	Status        string
	Currency      string
	AmountMin     *int64
	AmountMax     *int64
	CreatedAfter  string
	CreatedBefore string
	// Expand: the only expandable relation on this resource is "refunds".
	Expand string
	// Metadata: containment filter, spelled metadata[key]=value on the wire. Every named key
	// must match.
	Metadata map[string]string
}

func (p *ListPaymentsParams) query() map[string]any {
	if p == nil {
		return nil
	}
	return q{}.int64p("limit", p.Limit).str("status", p.Status).str("currency", p.Currency).
		int64p("amount_min", p.AmountMin).int64p("amount_max", p.AmountMax).
		str("created_after", p.CreatedAfter).str("created_before", p.CreatedBefore).
		str("expand", p.Expand).meta("metadata", p.Metadata)
}

// RefundParams is the body of Payments.Refund. All optional: a zero value refunds the full
// remaining amount.
type RefundParams struct {
	AmountMinor int64             `json:"amountMinor,omitempty"`
	Reason      string            `json:"reason,omitempty"`
	Metadata    map[string]string `json:"metadata,omitempty"`
}

// Create creates a payment. An Idempotency-Key is generated unless you pass WithIdempotencyKey.
func (s *PaymentsService) Create(ctx context.Context, params CreatePaymentParams, opts ...RequestOption) (*Payment, error) {
	return send[Payment](ctx, s.t, op("createPayment"), nil, nil, params, opts)
}

// Retrieve reads one payment. Pass expand="refunds" to include its refunds.
func (s *PaymentsService) Retrieve(ctx context.Context, id string, opts ...RequestOption) (*Payment, error) {
	return send[Payment](ctx, s.t, op("getPayment"), map[string]string{"id": id}, nil, nil, opts)
}

// RetrieveExpanded reads one payment with the given expand value ("refunds").
func (s *PaymentsService) RetrieveExpanded(ctx context.Context, id, expand string, opts ...RequestOption) (*Payment, error) {
	return send[Payment](ctx, s.t, op("getPayment"), map[string]string{"id": id},
		q{}.str("expand", expand), nil, opts)
}

// List lists your payments, most recent first. The result paginates transparently.
func (s *PaymentsService) List(ctx context.Context, params *ListPaymentsParams, opts ...RequestOption) (*CursorPage[Payment], error) {
	return listCursor[Payment](ctx, s.t, op("listPayments"), params.query(), opts)
}

// Authorize authorizes a created payment, reserving the funds.
func (s *PaymentsService) Authorize(ctx context.Context, id string, opts ...RequestOption) (*Payment, error) {
	return send[Payment](ctx, s.t, op("authorizePayment"), map[string]string{"id": id}, nil, nil, opts)
}

// Capture captures an authorized payment, moving the funds.
func (s *PaymentsService) Capture(ctx context.Context, id string, opts ...RequestOption) (*Payment, error) {
	return send[Payment](ctx, s.t, op("capturePayment"), map[string]string{"id": id}, nil, nil, opts)
}

// Refund refunds a captured payment, in full or in part.
//
// It returns the Payment, not the Refund — the refund is the newest entry in the payment's
// Refunds slice. That is what the endpoint returns; reshaping it here would mean a second
// request or a guess about which element is the new one.
func (s *PaymentsService) Refund(ctx context.Context, id string, params RefundParams, opts ...RequestOption) (*Payment, error) {
	return send[Payment](ctx, s.t, op("refundPayment"), map[string]string{"id": id}, nil, params, opts)
}

// Void voids an authorized payment, releasing the funds without capturing them.
func (s *PaymentsService) Void(ctx context.Context, id string, opts ...RequestOption) (*Payment, error) {
	return send[Payment](ctx, s.t, op("voidPayment"), map[string]string{"id": id}, nil, nil, opts)
}
