/**
 * The shapes `agentic-commerce-service` speaks on `/api/agentic/**`.
 *
 * Transcribed from `AgentDtos` and `ApprovalView` in that service. These are **not** part of the
 * published `/v1` contract and are not generated — the agentic surface is deliberately outside
 * `docs/openapi.yaml` (AD-8) — so this file is the hand-maintained mirror, and the reason the
 * proxy is a small fixed set of endpoints rather than the contract-driven `/api/platform` route.
 *
 * Every field here is already a redacted, structured projection the service chose to expose. No
 * credential, policy threshold or raw model output crosses this boundary.
 */

export type AgenticConversationStatus = 'ACTIVE' | 'CLOSED' | string;

export interface AgenticMessage {
  readonly role: string;
  readonly content: string;
  readonly sequenceNo: number;
  readonly createdAt: string;
}

export interface AgenticConversation {
  readonly id: string;
  readonly status: AgenticConversationStatus;
  readonly sessionRef: string;
  readonly spentMinor: number;
  readonly refundedMinor: number;
  readonly toolCallCount: number;
  readonly createdAt: string;
  readonly messages: readonly AgenticMessage[];
}

/** One tool call within a turn, as the turn response summarises it. */
export interface AgenticTurnAction {
  readonly actionId: string | null;
  readonly toolName: string;
  readonly state: string;
  readonly policyDecision: string | null;
  readonly ok: boolean;
  readonly errorCode: string | null;
  readonly message: string | null;
}

/**
 * One agent turn. `stopReason` is what the UI branches on — never `reply`, which is prose for
 * the customer and is not a source of financial fact.
 */
export interface AgenticTurn {
  readonly conversationId: string;
  readonly reply: string;
  readonly stopReason: string;
  readonly approvalId: string | null;
  readonly actions: readonly AgenticTurnAction[];
}

/** One platform call made under an action — carries the derived idempotency key as evidence. */
export interface AgenticActionStep {
  readonly sequenceNo: number;
  readonly operation: string;
  readonly state: string;
  readonly idempotencyKey: string | null;
  readonly requestId: string | null;
  readonly httpStatus: number | null;
  readonly paymentId: string | null;
  readonly failureCode: string | null;
  readonly createdAt: string;
  readonly completedAt: string | null;
}

/** The full trail for one action: model proposal → policy → approval → platform calls → result. */
export interface AgenticActionTrail {
  readonly id: string;
  readonly toolName: string;
  readonly toolCategory: string;
  readonly state: string;
  readonly policyDecision: string | null;
  readonly approvalId: string | null;
  readonly checkoutId: string | null;
  readonly paymentId: string | null;
  readonly inputSummary: string | null;
  readonly failureCode: string | null;
  readonly failureMessage: string | null;
  readonly budgetRemainingMinor: number | null;
  readonly correlationId: string | null;
  readonly llmModel: string | null;
  readonly promptVersion: string | null;
  readonly createdAt: string;
  readonly completedAt: string | null;
  readonly steps: readonly AgenticActionStep[];
}

export type AgenticApprovalState =
  'PENDING' | 'APPROVED' | 'DENIED' | 'EXPIRED' | 'CONSUMED' | string;

export interface AgenticApproval {
  readonly id: string;
  readonly agentActionId: string;
  readonly conversationId: string;
  readonly toolName: string;
  readonly operation: string;
  readonly checkoutId: string | null;
  readonly paymentId: string | null;
  readonly amountMinor: number | null;
  readonly currency: string | null;
  readonly state: AgenticApprovalState;
  readonly reason: string | null;
  readonly decidedBy: string | null;
  readonly createdAt: string;
  readonly expiresAt: string;
  readonly decidedAt: string | null;
}

/** The page envelope every agentic listing returns (page-number pagination). */
export interface AgenticPage<T> {
  readonly data: readonly T[];
  readonly page: number;
  readonly limit: number;
  readonly total: number;
  readonly hasMore: boolean;
}

// ── Catalog (G-2) ────────────────────────────────────────────────────────────

export interface AgenticProduct {
  readonly id: string;
  readonly sku: string;
  readonly name: string;
  readonly description: string | null;
  readonly category: string | null;
  readonly priceMinor: number;
  readonly currency: string;
  readonly available: boolean;
}

// ── Checkout (G-2) ───────────────────────────────────────────────────────────

export interface AgenticCheckoutLine {
  readonly productId: string;
  readonly sku: string;
  readonly name: string;
  readonly quantity: number;
  readonly unitPriceMinor: number;
  readonly lineTotalMinor: number;
}

export interface AgenticCheckout {
  readonly id: string;
  readonly status: string;
  readonly currency: string;
  readonly subtotalMinor: number;
  readonly discountMinor: number;
  readonly totalMinor: number;
  readonly lines: readonly AgenticCheckoutLine[];
  readonly paymentId: string | null;
  readonly expiresAt: string;
}

// ── Conversation list (G-4) ──────────────────────────────────────────────────

export interface AgenticConversationSummary {
  readonly id: string;
  readonly status: AgenticConversationStatus;
  readonly sessionRef: string;
  readonly spentMinor: number;
  readonly refundedMinor: number;
  readonly toolCallCount: number;
  readonly createdAt: string;
}

// ── Config / policy (G-3) ────────────────────────────────────────────────────

export interface AgenticPolicyRule {
  readonly id: string;
  readonly phase: string;
  readonly decision: 'PERMIT' | 'REFUSE' | 'REQUIRES_APPROVAL' | string;
  readonly reasonCode: string;
  readonly scope: string | null;
  readonly thresholdUnit: 'MINOR' | 'COUNT' | 'NONE' | string;
  readonly threshold: number | null;
  readonly disabled: boolean;
  readonly waivable: boolean;
  readonly description: string;
}

export interface AgenticConfig {
  readonly mode: string;
  readonly promptVersion: string;
  readonly llm: {
    readonly provider: string;
    readonly model: string;
    readonly credentialConfigured: boolean;
    readonly scriptedFallback: boolean;
    readonly maxToolIterations: number;
    readonly maxTurnDurationMs: number;
  };
  readonly checkout: { readonly ttlMinutes: number; readonly maxLineItems: number };
  readonly razorpay: {
    readonly credentialConfigured: boolean;
    readonly enabled: boolean;
    readonly uncollectedOrderOutcome: string;
  };
  readonly policy: {
    readonly version: string;
    readonly currency: string;
    readonly rules: readonly AgenticPolicyRule[];
  };
  readonly tools: readonly {
    readonly name: string;
    readonly category: string;
    readonly movesMoney: boolean;
    readonly description: string;
  }[];
}

// ── Summary / metrics (G-1) ──────────────────────────────────────────────────

export interface AgenticSummary {
  readonly window: { readonly from: string; readonly to: string };
  readonly conversations: { readonly total: number };
  readonly actions: {
    readonly total: number;
    readonly executed: number;
    readonly refused: number;
    readonly failed: number;
    readonly approvalRequired: number;
  };
  readonly policyDecisions: {
    readonly permit: number;
    readonly refuse: number;
    readonly requiresApproval: number;
  };
  readonly approvals: {
    readonly pending: number;
    readonly approved: number;
    readonly denied: number;
    readonly expired: number;
    readonly consumed: number;
  };
  readonly payments: { readonly agentInitiated: number };
  readonly source: string;
}

// ── Provider decisions (G-6) ─────────────────────────────────────────────────

export type AgenticProviderDecisionKind =
  'REAL_AUTHORIZATION' | 'DEMO_ORDER_ACCEPTED' | 'DECLINED' | 'ERRORED' | 'NOT_CONFIGURED' | string;

export interface AgenticProviderDecision {
  readonly id: string | null;
  readonly paymentId: string;
  readonly operation: string;
  readonly outcome: string;
  readonly kind: AgenticProviderDecisionKind;
  readonly demoApproval: boolean;
  readonly source: string;
  readonly declineCode: string | null;
  readonly errorCode: string | null;
  readonly providerReference: string | null;
  readonly providerName: string;
  readonly amountMinor: number;
  readonly currency: string;
  readonly correlationId: string | null;
  readonly createdAt: string;
}
