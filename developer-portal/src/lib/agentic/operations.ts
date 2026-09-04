import 'server-only';

import { type MerchantSession } from '@/lib/session/require';

import { callAgentic } from './client';
import {
  type AgenticActionTrail,
  type AgenticApproval,
  type AgenticCheckout,
  type AgenticConfig,
  type AgenticConversation,
  type AgenticConversationSummary,
  type AgenticPage,
  type AgenticProduct,
  type AgenticProviderDecision,
  type AgenticSummary,
  type AgenticTurn,
} from './types';

/**
 * The typed operations the portal performs against `agentic-commerce-service`, each a thin wrap
 * of {@link callAgentic}. This is the whole surface — it mirrors the service's `AgentController`
 * and `ApprovalController` and nothing else, because those two controllers are the whole of what
 * the service exposes on `/api/agentic/**`.
 *
 * The session is always passed through; identity is asserted from it in the signed context and
 * never taken from a parameter.
 */

/** Start a conversation. `sessionRef` scopes it to a browser session; it is not a credential. */
export function startConversation(
  session: MerchantSession,
  sessionRef: string,
): Promise<AgenticConversation> {
  return callAgentic<AgenticConversation>(session, {
    method: 'POST',
    path: '/api/agentic/conversations',
    body: { sessionRef },
  });
}

export function getConversation(
  session: MerchantSession,
  conversationId: string,
): Promise<AgenticConversation> {
  return callAgentic<AgenticConversation>(session, {
    method: 'GET',
    path: `/api/agentic/conversations/${encodeURIComponent(conversationId)}`,
  });
}

export function getConversationActions(
  session: MerchantSession,
  conversationId: string,
): Promise<AgenticActionTrail[]> {
  return callAgentic<AgenticActionTrail[]>(session, {
    method: 'GET',
    path: `/api/agentic/conversations/${encodeURIComponent(conversationId)}/actions`,
  });
}

/** One agent turn. Always resolves on a completed turn — an approval stop is a value, not a throw. */
export function sendMessage(
  session: MerchantSession,
  conversationId: string,
  message: string,
  correlationId?: string,
): Promise<AgenticTurn> {
  return callAgentic<AgenticTurn>(session, {
    method: 'POST',
    path: `/api/agentic/conversations/${encodeURIComponent(conversationId)}/messages`,
    body: { message },
    ...(correlationId ? { correlationId } : {}),
  });
}

export function listApprovals(session: MerchantSession): Promise<AgenticApproval[]> {
  return callAgentic<AgenticApproval[]>(session, {
    method: 'GET',
    path: '/api/agentic/approvals',
  });
}

export function getApproval(
  session: MerchantSession,
  approvalId: string,
): Promise<AgenticApproval> {
  return callAgentic<AgenticApproval>(session, {
    method: 'GET',
    path: `/api/agentic/approvals/${encodeURIComponent(approvalId)}`,
  });
}

/**
 * Grant an approval. This **executes the action it was granted for**, immediately and once —
 * the agentic service re-resolves the facts, re-evaluates policy and redeems the approval — so
 * the result is an agent turn, not just an updated approval.
 */
export function approveApproval(
  session: MerchantSession,
  approvalId: string,
  decidedBy: string,
): Promise<AgenticTurn> {
  return callAgentic<AgenticTurn>(session, {
    method: 'POST',
    path: `/api/agentic/approvals/${encodeURIComponent(approvalId)}/approve`,
    body: { decidedBy, reason: null },
  });
}

/** Deny an approval. Terminal; nothing financial happens now or later under it. */
export function denyApproval(
  session: MerchantSession,
  approvalId: string,
  decidedBy: string,
  reason: string | null,
): Promise<AgenticApproval> {
  return callAgentic<AgenticApproval>(session, {
    method: 'POST',
    path: `/api/agentic/approvals/${encodeURIComponent(approvalId)}/deny`,
    body: { decidedBy, reason },
  });
}

// ── G-1/2/3/4/6 read operations ──────────────────────────────────────────────

function pageQuery(page: number | undefined, limit: number | undefined): string {
  const params = new URLSearchParams();
  if (page !== undefined) params.set('page', String(page));
  if (limit !== undefined) params.set('limit', String(limit));
  const q = params.toString();
  return q ? `?${q}` : '';
}

/** G-2 — the merchant catalogue. A `query` triggers the capped text search (no paging). */
export function listProducts(
  session: MerchantSession,
  opts: {
    query?: string | undefined;
    category?: string | undefined;
    page?: number | undefined;
    limit?: number | undefined;
  } = {},
): Promise<AgenticPage<AgenticProduct>> {
  const params = new URLSearchParams();
  if (opts.query) params.set('query', opts.query);
  if (opts.category) params.set('category', opts.category);
  if (opts.page !== undefined) params.set('page', String(opts.page));
  if (opts.limit !== undefined) params.set('limit', String(opts.limit));
  const q = params.toString();
  return callAgentic(session, {
    method: 'GET',
    path: `/api/agentic/catalog/products${q ? `?${q}` : ''}`,
  });
}

export function getProduct(session: MerchantSession, id: string): Promise<AgenticProduct> {
  return callAgentic(session, {
    method: 'GET',
    path: `/api/agentic/catalog/products/${encodeURIComponent(id)}`,
  });
}

export function listProductCategories(session: MerchantSession): Promise<string[]> {
  return callAgentic(session, { method: 'GET', path: '/api/agentic/catalog/categories' });
}

/** G-2 — checkouts the agent assembled. */
export function listCheckouts(
  session: MerchantSession,
  opts: { page?: number | undefined; limit?: number | undefined } = {},
): Promise<AgenticPage<AgenticCheckout>> {
  return callAgentic(session, {
    method: 'GET',
    path: `/api/agentic/checkouts${pageQuery(opts.page, opts.limit)}`,
  });
}

export function getCheckout(session: MerchantSession, id: string): Promise<AgenticCheckout> {
  return callAgentic(session, {
    method: 'GET',
    path: `/api/agentic/checkouts/${encodeURIComponent(id)}`,
  });
}

/** G-4 — the conversation list. */
export function listConversations(
  session: MerchantSession,
  opts: { page?: number | undefined; limit?: number | undefined } = {},
): Promise<AgenticPage<AgenticConversationSummary>> {
  return callAgentic(session, {
    method: 'GET',
    path: `/api/agentic/conversations${pageQuery(opts.page, opts.limit)}`,
  });
}

/** G-4 — the cross-conversation action index. */
export function listActions(
  session: MerchantSession,
  opts: {
    paymentId?: string | undefined;
    page?: number | undefined;
    limit?: number | undefined;
  } = {},
): Promise<AgenticPage<AgenticActionTrail>> {
  const params = new URLSearchParams();
  if (opts.paymentId) params.set('payment_id', opts.paymentId);
  if (opts.page !== undefined) params.set('page', String(opts.page));
  if (opts.limit !== undefined) params.set('limit', String(opts.limit));
  const q = params.toString();
  return callAgentic(session, {
    method: 'GET',
    path: `/api/agentic/actions${q ? `?${q}` : ''}`,
  });
}

export function getAction(session: MerchantSession, id: string): Promise<AgenticActionTrail> {
  return callAgentic(session, {
    method: 'GET',
    path: `/api/agentic/actions/${encodeURIComponent(id)}`,
  });
}

/** G-3 — the runtime configuration and the policy the engine enforces. */
export function getAgenticConfig(session: MerchantSession): Promise<AgenticConfig> {
  return callAgentic(session, { method: 'GET', path: '/api/agentic/config' });
}

/** G-1 — the persisted aggregate. */
export function getAgenticSummary(
  session: MerchantSession,
  opts: { from?: string | undefined; to?: string | undefined } = {},
): Promise<AgenticSummary> {
  const params = new URLSearchParams();
  if (opts.from) params.set('from', opts.from);
  if (opts.to) params.set('to', opts.to);
  const q = params.toString();
  return callAgentic(session, {
    method: 'GET',
    path: `/api/agentic/summary${q ? `?${q}` : ''}`,
  });
}

/** G-6 — persisted provider decisions, real vs demo distinguished by `kind`. */
export function listProviderDecisions(
  session: MerchantSession,
  opts: {
    paymentId?: string | undefined;
    page?: number | undefined;
    limit?: number | undefined;
  } = {},
): Promise<AgenticPage<AgenticProviderDecision>> {
  const params = new URLSearchParams();
  if (opts.paymentId) params.set('payment_id', opts.paymentId);
  if (opts.page !== undefined) params.set('page', String(opts.page));
  if (opts.limit !== undefined) params.set('limit', String(opts.limit));
  const q = params.toString();
  return callAgentic(session, {
    method: 'GET',
    path: `/api/agentic/provider-decisions${q ? `?${q}` : ''}`,
  });
}
