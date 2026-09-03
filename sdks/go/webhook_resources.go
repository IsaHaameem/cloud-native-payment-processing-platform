package paymentflow

import "context"

// WebhookEndpointsService is client.WebhookEndpoints — where events are delivered, and their
// signing secrets. Create and RotateSecret return a *WebhookEndpointCreated carrying the
// SigningSecret; Retrieve and List return a *WebhookEndpoint that does not. That difference is
// the platform's — the secret is sent exactly once.
type WebhookEndpointsService struct{ t *transport }

// CreateWebhookEndpointParams is the body of WebhookEndpoints.Create.
type CreateWebhookEndpointParams struct {
	// URL is where to deliver events. Must be reachable over HTTPS from the public internet.
	URL string `json:"url"`
	// EnabledEvents is the event types to send here. At least one; ["*"] for everything.
	EnabledEvents []string          `json:"enabledEvents"`
	Description   string            `json:"description,omitempty"`
	Metadata      map[string]string `json:"metadata,omitempty"`
}

// UpdateWebhookEndpointParams is the body of WebhookEndpoints.Update. Every field optional; send
// only what changes. A pointer bool so "leave enabled unchanged" differs from "set enabled to
// false".
type UpdateWebhookEndpointParams struct {
	Enabled       *bool             `json:"enabled,omitempty"`
	EnabledEvents []string          `json:"enabledEvents,omitempty"`
	Description   string            `json:"description,omitempty"`
	Metadata      map[string]string `json:"metadata,omitempty"`
}

// Create creates an endpoint and returns it with its signing secret. Store the secret now.
func (s *WebhookEndpointsService) Create(ctx context.Context, params CreateWebhookEndpointParams, opts ...RequestOption) (*WebhookEndpointCreated, error) {
	return send[WebhookEndpointCreated](ctx, s.t, op("createWebhookEndpoint"), nil, nil, params, opts)
}

// Retrieve reads one endpoint. Never includes the signing secret.
func (s *WebhookEndpointsService) Retrieve(ctx context.Context, id string, opts ...RequestOption) (*WebhookEndpoint, error) {
	return send[WebhookEndpoint](ctx, s.t, op("getWebhookEndpoint"), map[string]string{"id": id}, nil, nil, opts)
}

// List lists your endpoints. A plain slice — this endpoint is not paginated on the wire.
func (s *WebhookEndpointsService) List(ctx context.Context, opts ...RequestOption) ([]WebhookEndpoint, error) {
	return sendList[WebhookEndpoint](ctx, s.t, op("listWebhookEndpoints"), nil, opts)
}

// Update updates an endpoint.
func (s *WebhookEndpointsService) Update(ctx context.Context, id string, params UpdateWebhookEndpointParams, opts ...RequestOption) (*WebhookEndpoint, error) {
	return send[WebhookEndpoint](ctx, s.t, op("updateWebhookEndpoint"), map[string]string{"id": id}, nil, params, opts)
}

// Delete deletes an endpoint. The API returns 204.
func (s *WebhookEndpointsService) Delete(ctx context.Context, id string, opts ...RequestOption) error {
	return sendNoContent(ctx, s.t, op("deleteWebhookEndpoint"), map[string]string{"id": id}, opts)
}

// RotateSecret issues a new signing secret and returns it. As with Create, sent only once.
func (s *WebhookEndpointsService) RotateSecret(ctx context.Context, id string, opts ...RequestOption) (*WebhookEndpointCreated, error) {
	return send[WebhookEndpointCreated](ctx, s.t, op("rotateWebhookEndpointSecret"), map[string]string{"id": id}, nil, nil, opts)
}

// WebhookDeliveriesService is client.WebhookDeliveries — what happened when an event was
// delivered. Offset-paginated, not cursor-paginated: D139's deliberate exception.
type WebhookDeliveriesService struct{ t *transport }

// ListWebhookDeliveriesParams filters WebhookDeliveries.List.
type ListWebhookDeliveriesParams struct {
	Size *int
	Sort []string
}

func (p *ListWebhookDeliveriesParams) query() map[string]any {
	if p == nil {
		return nil
	}
	return q{}.intp("size", p.Size).strs("sort", p.Sort)
}

// Retrieve reads one delivery, including its attempts.
func (s *WebhookDeliveriesService) Retrieve(ctx context.Context, id string, opts ...RequestOption) (*WebhookDelivery, error) {
	return send[WebhookDelivery](ctx, s.t, op("getWebhookDelivery"), map[string]string{"id": id}, nil, nil, opts)
}

// List lists deliveries. The result paginates transparently.
func (s *WebhookDeliveriesService) List(ctx context.Context, params *ListWebhookDeliveriesParams, opts ...RequestOption) (*OffsetPage[WebhookDelivery], error) {
	return listOffset[WebhookDelivery](ctx, s.t, op("listWebhookDeliveries"), nil, params.query(), opts)
}

// Replay re-sends a delivery. Returns the new delivery, not the original.
func (s *WebhookDeliveriesService) Replay(ctx context.Context, id string, opts ...RequestOption) (*WebhookDelivery, error) {
	return send[WebhookDelivery](ctx, s.t, op("replayWebhookDelivery"), map[string]string{"id": id}, nil, nil, opts)
}
