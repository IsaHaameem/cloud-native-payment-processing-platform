import 'server-only';

import { randomUUID } from 'node:crypto';

import { API_VERSION } from '@/generated/contract';
import { OPERATIONS, type OperationDescriptor } from '@/generated/operations';
import { env } from '@/lib/env';

import { type PlatformError, ConnectionError, toPlatformError } from './errors';

/**
 * The portal's one door to the platform (M23.1, D188).
 *
 * ── Why this is not the Node SDK ──────────────────────────────────────────────────────
 *
 * `sdks/node` is a finished, tested client and the portal deliberately does not use it. Its
 * `apiKey` option is required and the portal has a session, not a key; adding a session mode to
 * a *public* package would put an option there that no integrator can ever use. And its retry
 * policy is right for a program calling across the internet and wrong for a server sitting next
 * to the gateway on behalf of a human who clicked once.
 *
 * What it *does* reuse is the part that matters: the generated operation descriptors. Paths,
 * query-parameter names and which operations require an `Idempotency-Key` are all read from the
 * contract (D166) rather than from a list this file would keep, and keep answering the old
 * question after the contract moved.
 *
 * ── What M23.1 builds, and what it does not ───────────────────────────────────────────
 *
 * Everything here is credential-free. The session cookie, the bearer token and the mode header
 * arrive in M23.2 through {@link RequestCredentials}; this module already threads that shape
 * through so the later change is an implementation, not a redesign. Nothing in the portal calls
 * it yet — the first caller is M23.3's data layer.
 */

/** Per-request identity, supplied by the caller. Populated by the session module in M23.2. */
export interface RequestCredentials {
  /** The session's access token, sent as `Authorization: Bearer …`. */
  readonly accessToken: string;
  /** `test` or `live`, from the session cookie. The gateway validates it (M23.0, D184). */
  readonly mode: 'test' | 'live';
}

export interface CallOptions {
  readonly credentials: RequestCredentials;
  /** Values for the `{placeholders}` in the operation's path template. */
  readonly path?: Readonly<Record<string, string>>;
  /** Query parameters. Validated against the descriptor before the request is built. */
  readonly query?: Readonly<Record<string, string | number | boolean | undefined>>;
  readonly body?: unknown;
  /**
   * Reused across a retry of the *same* user action so a double-click cannot double-charge.
   * Generated here when the operation requires one and the caller did not supply it.
   */
  readonly idempotencyKey?: string;
  /** Correlation id to propagate, so a portal action and its platform trace share one thread. */
  readonly correlationId?: string;
  readonly signal?: AbortSignal;
}

/** One HTTP attempt may take this long. Short: the gateway is one hop away, not across an ocean. */
const TIMEOUT_MS = 15_000;

/**
 * Retried statuses, and only for operations that are safe to replay.
 *
 * Deliberately narrower than the SDK's. The SDK retries 429 and every 5xx with full jitter,
 * which is correct for unattended software. Here a human is waiting, a failed dashboard read is
 * cheap to retry by hand, and retrying a *mutation* the platform does not deduplicate is how a
 * refund happens twice. So: idempotent methods only, transient gateway statuses only, once.
 */
const RETRYABLE_STATUSES = new Set([502, 503, 504]);
const SAFE_METHODS = new Set(['GET', 'HEAD']);
const MAX_RETRIES = 1;

export class UnknownOperationError extends Error {}
export class UnknownQueryParameterError extends Error {}
export class MissingPathParameterError extends Error {}

export type OperationId = keyof typeof OPERATIONS;

/**
 * Calls one published operation.
 *
 * @throws {PlatformError} for any non-2xx answer, classified by `ApiError.type`
 */
export async function call<T>(operationId: OperationId, options: CallOptions): Promise<T> {
  const descriptor = OPERATIONS[operationId] as OperationDescriptor | undefined;
  if (!descriptor) {
    throw new UnknownOperationError(`No published operation named "${operationId}".`);
  }

  const url = buildUrl(descriptor, options);
  const headers = buildHeaders(descriptor, options);
  const init: RequestInit = {
    method: descriptor.method,
    headers,
    // Platform responses are per-merchant and per-mode. Caching one would serve another
    // merchant's page, so this is not a performance choice.
    cache: 'no-store',
  };
  if (options.body !== undefined) {
    init.body = JSON.stringify(options.body);
  }

  const replayable = SAFE_METHODS.has(descriptor.method);
  let lastError: PlatformError | undefined;

  for (let attempt = 0; attempt <= (replayable ? MAX_RETRIES : 0); attempt++) {
    const result = await attemptOnce<T>(url, init, descriptor, options.signal);
    if (result.ok) return result.value;

    lastError = result.error;
    const retryable =
      result.error instanceof ConnectionError || RETRYABLE_STATUSES.has(result.error.status);
    if (!retryable) throw result.error;
  }

  // Unreachable unless the loop above ran at least once, which it always does.
  throw (
    lastError ??
    new ConnectionError('The request could not be completed.', {
      status: 0,
      body: undefined,
      operationId,
    })
  );
}

type Attempt<T> = { ok: true; value: T } | { ok: false; error: PlatformError };

async function attemptOnce<T>(
  url: string,
  init: RequestInit,
  descriptor: OperationDescriptor,
  signal: AbortSignal | undefined,
): Promise<Attempt<T>> {
  const timeout = AbortSignal.timeout(TIMEOUT_MS);
  const combined = signal ? AbortSignal.any([signal, timeout]) : timeout;

  let response: Response;
  try {
    response = await fetch(url, { ...init, signal: combined });
  } catch (cause) {
    // A response that never arrived does not mean a request that never arrived (D169) — which
    // is exactly why only safe methods reach the retry above.
    return {
      ok: false,
      error: new ConnectionError(
        cause instanceof Error ? cause.message : 'The platform could not be reached.',
        { status: 0, body: undefined, operationId: descriptor.id },
      ),
    };
  }

  if (response.status === 204 || descriptor.successStatus === '204') {
    return { ok: true, value: undefined as T };
  }

  const text = await response.text();
  const parsed = text.length > 0 ? safeJson(text) : undefined;

  if (!response.ok) {
    return {
      ok: false,
      error: toPlatformError({
        status: response.status,
        body: isApiErrorShaped(parsed) ? parsed : undefined,
        operationId: descriptor.id,
      }),
    };
  }
  return { ok: true, value: parsed as T };
}

function buildUrl(descriptor: OperationDescriptor, options: CallOptions): string {
  const path = descriptor.path.replace(/\{([^}]+)\}/g, (_match, name: string) => {
    const value = options.path?.[name];
    if (value === undefined) {
      throw new MissingPathParameterError(
        `${descriptor.id} needs a path parameter "${name}" and none was supplied.`,
      );
    }
    return encodeURIComponent(value);
  });

  const search = new URLSearchParams();
  for (const [name, value] of Object.entries(options.query ?? {})) {
    if (value === undefined) continue;
    // Checked against the contract rather than passed through: a typo'd filter name is
    // ignored by the platform, which answers with a correct-looking *unfiltered* page. That
    // is the failure worth turning into an exception.
    if (!descriptor.queryParameters.includes(name)) {
      throw new UnknownQueryParameterError(
        `${descriptor.id} does not accept a query parameter named "${name}".`,
      );
    }
    search.set(name, String(value));
  }

  const query = search.toString();
  return `${env.gatewayUrl}${path}${query ? `?${query}` : ''}`;
}

function buildHeaders(descriptor: OperationDescriptor, options: CallOptions): Headers {
  const headers = new Headers({
    Accept: 'application/json',
    Authorization: `Bearer ${options.credentials.accessToken}`,
    // Always explicit, never inherited from the merchant's pin: the portal's components are
    // built against the current revision's shapes and enum spellings, and a merchant pinned to
    // a superseded one would otherwise be served a UI that cannot read their data.
    'PaymentFlow-Version': API_VERSION,
    // The gateway validates this and consumes it — it is never forwarded downstream (M23.0).
    'X-PF-Mode': options.credentials.mode,
  });

  if (descriptor.hasRequestBody) {
    headers.set('Content-Type', 'application/json');
  }
  if (options.correlationId) {
    headers.set('X-Correlation-Id', options.correlationId);
  }
  // Which operations need a key is read from the contract, not from a list kept here (D166).
  if (descriptor.requiredHeaders.includes('Idempotency-Key')) {
    headers.set('Idempotency-Key', options.idempotencyKey ?? randomUUID());
  }
  return headers;
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
