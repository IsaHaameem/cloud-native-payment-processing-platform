package paymentflow

import "context"

// EventsService is client.Events — the event log behind your webhooks.
type EventsService struct{ t *transport }

// ListEventsParams filters Events.List.
type ListEventsParams struct {
	Limit         *int64
	Type          string
	CreatedAfter  string
	CreatedBefore string
}

func (p *ListEventsParams) query() map[string]any {
	if p == nil {
		return nil
	}
	return q{}.int64p("limit", p.Limit).str("type", p.Type).
		str("created_after", p.CreatedAfter).str("created_before", p.CreatedBefore)
}

// Retrieve reads one event. Its ID matches the id in the webhook body for the same event.
func (s *EventsService) Retrieve(ctx context.Context, id string, opts ...RequestOption) (*Event, error) {
	return send[Event](ctx, s.t, op("getEvent"), map[string]string{"id": id}, nil, nil, opts)
}

// List lists events, most recent first. The result paginates transparently.
func (s *EventsService) List(ctx context.Context, params *ListEventsParams, opts ...RequestOption) (*CursorPage[Event], error) {
	return listCursor[Event](ctx, s.t, op("listEvents"), params.query(), opts)
}
