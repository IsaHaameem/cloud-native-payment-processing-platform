package paymentflow

import "context"

// TestHelpersService is client.TestHelpers — the sandbox controls. Six operations that only
// exist in test mode, grouped so the call site signals it: none of this works with a live key.
//
// The mode is decided by the key alone. This SDK has no mode option and will not get one — a
// switch that appeared to move a client between test and live would be a lie, because the
// platform ignores everything except which key was presented.
type TestHelpersService struct{ t *transport }

// CreateSimulationOverrideParams is the body of TestHelpers.CreateSimulationOverride.
type CreateSimulationOverrideParams struct {
	// Scenario is which behaviour to force. Required. See
	// CreateSimulationOverrideRequestScenarioValues.
	Scenario        string `json:"scenario"`
	DeclineCode     string `json:"declineCode,omitempty"`
	ErrorCode       string `json:"errorCode,omitempty"`
	LatencyMs       int64  `json:"latencyMs,omitempty"`
	RemainingCount  int64  `json:"remainingCount,omitempty"`
	DurationSeconds int64  `json:"durationSeconds,omitempty"`
}

// ListDecisionsParams filters TestHelpers.ListDecisions.
type ListDecisionsParams struct {
	Size *int
	Sort []string
}

func (p *ListDecisionsParams) query() map[string]any {
	if p == nil {
		return nil
	}
	return q{}.intp("size", p.Size).strs("sort", p.Sort)
}

// ListCards lists the seeded test cards and what each one does. A plain slice; the catalogue is
// small and fixed and is not paginated on the wire.
func (s *TestHelpersService) ListCards(ctx context.Context, opts ...RequestOption) ([]TestCard, error) {
	return sendList[TestCard](ctx, s.t, op("listTestCards"), nil, opts)
}

// ListDecisions lists authorization decisions the sandbox made, and why. Offset-paginated (D139).
func (s *TestHelpersService) ListDecisions(ctx context.Context, params *ListDecisionsParams, opts ...RequestOption) (*OffsetPage[DecisionLogEntry], error) {
	return listOffset[DecisionLogEntry](ctx, s.t, op("listSandboxDecisions"), nil, params.query(), opts)
}

// ListDecisionsForPayment lists the decisions made for one payment. A plain slice — one
// payment's decisions are few and the endpoint returns them all.
func (s *TestHelpersService) ListDecisionsForPayment(ctx context.Context, paymentID string, opts ...RequestOption) ([]DecisionLogEntry, error) {
	return sendList[DecisionLogEntry](ctx, s.t, op("listSandboxDecisionsForPayment"),
		map[string]string{"paymentId": paymentID}, opts)
}

// CreateSimulationOverride forces a behaviour for subsequent authorizations, replacing any
// active override.
func (s *TestHelpersService) CreateSimulationOverride(ctx context.Context, params CreateSimulationOverrideParams, opts ...RequestOption) (*SimulationOverride, error) {
	return send[SimulationOverride](ctx, s.t, op("createSimulationOverride"), nil, nil, params, opts)
}

// RetrieveActiveSimulationOverride reads the active override.
func (s *TestHelpersService) RetrieveActiveSimulationOverride(ctx context.Context, opts ...RequestOption) (*SimulationOverride, error) {
	return send[SimulationOverride](ctx, s.t, op("getActiveSimulationOverride"), nil, nil, nil, opts)
}

// RevokeActiveSimulationOverride revokes the active override. The API returns 204.
func (s *TestHelpersService) RevokeActiveSimulationOverride(ctx context.Context, opts ...RequestOption) error {
	return sendNoContent(ctx, s.t, op("revokeActiveSimulationOverride"), nil, opts)
}
