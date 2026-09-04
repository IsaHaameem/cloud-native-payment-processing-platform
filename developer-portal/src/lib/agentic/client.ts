import 'server-only';

import { randomUUID } from 'node:crypto';

import { ConnectionError, toPlatformError } from '@/lib/api/errors';
import { env } from '@/lib/env';
import { type MerchantSession } from '@/lib/session/require';

import { signedInternalContextHeaders } from './internal-context';

/**
 * The one authenticated path from the portal to `agentic-commerce-service` (M-agentic, D100).
 *
 * Analogous to `lib/api/client.ts`'s `callAs` for the gateway, and deliberately smaller. The
 * agentic surface is a handful of endpoints, it is one hop away on the same host, and — unlike
 * the gateway path — there is no token to refresh: the credential is an HMAC signature minted
 * fresh for each call from the sealed session, by {@link signedInternalContextHeaders}.
 *
 * ── What it will and will not do ──────────────────────────────────────────────────────
 *
 * **Merchant, mode and user come from the session**, never from a caller-supplied value — they
 * are what the signed context asserts, and the agentic service refuses a context whose merchant
 * is not the one it is bound to. A route handler or Server Action passes the `MerchantSession`
 * and a path; it cannot pass an identity.
 *
 * **No retry.** A failed read is cheap to retry by hand; a failed mutation must not be replayed
 * blind (D169). The agent turn and approval endpoints are not idempotent at this layer — the
 * determinism is the agentic service's, keyed on the conversation and tool.
 *
 * **Never cached.** Every response is per-merchant and per-conversation.
 */

/** One attempt may take this long. The agent turn endpoint can be slow — it may call a model. */
const TIMEOUT_MS = 120_000;

export interface AgenticCallOptions {
  readonly method: 'GET' | 'POST';
  /** An absolute path on the agentic service, e.g. `/api/agentic/approvals`. */
  readonly path: string;
  readonly body?: unknown;
  /** Propagated so a portal action and its agentic trace share one thread. */
  readonly correlationId?: string;
  readonly signal?: AbortSignal;
}

/**
 * Calls the agentic service on behalf of a session.
 *
 * @throws {PlatformError} for any non-2xx answer, classified by the platform `ApiError.type`
 *                         the agentic service returns via `common-lib`'s exception handler
 * @throws {ConnectionError} the request got no answer
 */
export async function callAgentic<T>(
  session: MerchantSession,
  options: AgenticCallOptions,
): Promise<T> {
  const url = `${env.agenticServiceUrl}${options.path}`;
  const correlationId = options.correlationId ?? randomUUID();

  const headers = new Headers({
    Accept: 'application/json',
    'X-Correlation-Id': correlationId,
    ...signedInternalContextHeaders({
      merchantId: session.merchantId,
      mode: session.mode,
      userId: session.userId,
    }),
  });

  const init: RequestInit = { method: options.method, headers, cache: 'no-store' };
  if (options.body !== undefined) {
    headers.set('Content-Type', 'application/json');
    init.body = JSON.stringify(options.body);
  }

  const timeout = AbortSignal.timeout(TIMEOUT_MS);
  init.signal = options.signal ? AbortSignal.any([options.signal, timeout]) : timeout;

  let response: Response;
  try {
    response = await fetch(url, init);
  } catch (cause) {
    throw new ConnectionError(
      cause instanceof Error ? cause.message : 'The agentic service could not be reached.',
      { status: 0, body: undefined, operationId: `agentic ${options.method} ${options.path}` },
    );
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  const parsed = text.length > 0 ? safeJson(text) : undefined;

  if (!response.ok) {
    throw toPlatformError({
      status: response.status,
      body: isApiErrorShaped(parsed) ? parsed : undefined,
      operationId: `agentic ${options.method} ${options.path}`,
    });
  }
  return parsed as T;
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

function isApiErrorShaped(value: unknown): value is import('@/generated/models').ApiError {
  return typeof value === 'object' && value !== null && 'code' in value && 'message' in value;
}
