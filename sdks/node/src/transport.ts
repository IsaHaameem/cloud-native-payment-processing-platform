/**
 * The request pipeline: one HTTP attempt, wrapped in the retry loop that makes it safe (M22.2).
 *
 * ## The one property this file exists to hold
 *
 * A retried mutation must reuse the `Idempotency-Key` of the attempt it is retrying. The key is
 * therefore generated **once per logical call**, before the loop, and never inside it. §7.1
 * calls this the SDK's single most important correctness property, and it is: a key regenerated
 * per attempt turns "the platform deduplicated your retry" into "you charged the customer
 * twice", and it does so only under the network conditions that make retries happen — which is
 * to say, never in a test anyone wrote by hand and always in production.
 *
 * ## What is safe to retry
 *
 * Not "429 and 5xx". A response never arriving does not mean the request never arrived, so
 * replaying a call the platform does not deduplicate can perform it twice. This SDK retries
 * only what it can replay safely (D169): `GET` and `DELETE`, which HTTP defines as idempotent,
 * and any request carrying an `Idempotency-Key`. `POST /v1/webhook_endpoints` is retried by
 * neither rule, because a second attempt would create a second endpoint.
 */

import { randomUUID } from 'node:crypto';
import type { OperationDescriptor } from './generated/operations.js';
import type { ResolvedConfig } from './config.js';
import { ApiConnectionError, errorFromResponse, PaymentFlowError } from './errors.js';

/** The header the platform deduplicates mutations on. */
const IDEMPOTENCY_HEADER = 'Idempotency-Key';

/** How long a computed backoff may grow to, and the unit the exponential is built from. */
const BASE_BACKOFF_MS = 500;
const MAX_BACKOFF_MS = 8_000;

/**
 * The longest `Retry-After` this SDK will wait out rather than surrender to.
 *
 * `Retry-After` is authoritative, but for `DAILY_QUOTA_EXCEEDED` it is the time remaining until
 * 00:00 UTC — up to twenty-four hours. Sleeping that inside a caller's request handler is not
 * "honouring the header", it is a hang. Past this bound the SDK stops retrying and raises a
 * {@link RateLimitError} carrying `retryAfterSeconds`, so the caller can schedule the work
 * instead of blocking on it (D168).
 */
const MAX_HONOURED_RETRY_AFTER_MS = 60_000;

/** Per-call options every resource method accepts. */
export interface RequestOptions {
  /**
   * The idempotency key to send, for operations that take one.
   *
   * Supply your own when the retry must survive your *process* restarting, not just this
   * SDK's loop — a key generated here is lost with the promise that held it. Omit it and one
   * is generated per call.
   */
  readonly idempotencyKey?: string;

  /**
   * Your own identifier for this operation, sent as `X-Correlation-Id` and echoed back.
   *
   * This is how a trace that starts in your system joins PaymentFlow's logs. Omit it and the
   * platform generates one, which you can still read from the response.
   */
  readonly correlationId?: string;

  /** Overrides the client's timeout for this call only, in milliseconds. */
  readonly timeout?: number;

  /** Overrides the client's retry budget for this call only. */
  readonly maxRetries?: number;

  /** Cancels the call, including any retry it is waiting to make. */
  readonly signal?: AbortSignal;
}

/** What a resource method hands the transport. Assembled from a generated descriptor. */
export interface RequestSpec {
  readonly operation: OperationDescriptor;
  /** Values for the `{...}` placeholders in the operation's path template. */
  readonly path?: Readonly<Record<string, string>> | undefined;
  /** Query parameters, in wire spelling. `undefined` values are omitted, not sent empty. */
  readonly query?: Readonly<Record<string, unknown>> | undefined;
  /** The JSON request body, for operations that take one. */
  readonly body?: unknown;
  readonly options?: RequestOptions | undefined;
}

/** Everything a caller can learn about the exchange, beyond the body. */
export interface ResponseMeta {
  readonly statusCode: number;
  /** Identifies this one HTTP call, and keys the matching `GET /v1/request_logs` row. */
  readonly requestId: string | undefined;
  /** Identifies the whole distributed trace. */
  readonly correlationId: string | undefined;
  /** The dated API revision that answered. Absent when the request was refused at the edge. */
  readonly apiVersion: string | undefined;
  /** `true` when the revision that answered has been superseded. */
  readonly deprecated: boolean;
  /** Daily quota telemetry, when the response was measured against an allowance. */
  readonly rateLimit: RateLimitMeta | undefined;
  /** How many HTTP attempts this call took. 1 when it succeeded first time. */
  readonly attempts: number;
}

/** The daily quota, as reported on a measured response. */
export interface RateLimitMeta {
  readonly limit: number | undefined;
  readonly remaining: number | undefined;
  /**
   * Seconds until the daily quota window resets, at 00:00 UTC.
   *
   * Telemetry, not a retry hint — see {@link MAX_HONOURED_RETRY_AFTER_MS} and D167. It
   * describes the daily window even on a successful response, so treating it as "wait this
   * long" would idle a healthy client until midnight.
   */
  readonly resetSeconds: number | undefined;
}

/** A parsed response and what the transport learned alongside it. */
export interface TransportResult<T> {
  readonly data: T;
  readonly meta: ResponseMeta;
}

export class Transport {
  constructor(private readonly config: ResolvedConfig) {}

  async request<T>(spec: RequestSpec): Promise<TransportResult<T>> {
    const options = spec.options ?? {};
    const url = this.buildUrl(spec);
    const headers = this.buildHeaders(spec, options);
    const maxRetries = options.maxRetries ?? this.config.maxRetries;
    const timeout = options.timeout ?? this.config.timeout;

    const init: RequestInit = { method: spec.operation.method, headers };
    if (spec.operation.hasRequestBody && spec.body !== undefined) {
      init.body = JSON.stringify(spec.body);
    }
    const replayable = this.isReplayable(spec.operation, headers);

    let attempt = 0;
    for (;;) {
      attempt += 1;
      const outcome = await this.attempt<T>(url, init, timeout, attempt, options.signal);

      if (outcome.kind === 'success') {
        return outcome.result;
      }

      const remaining = maxRetries - (attempt - 1);
      const delay = remaining > 0 && replayable ? retryDelay(outcome, attempt) : undefined;
      if (delay === undefined) {
        throw outcome.error;
      }
      await sleep(delay, options.signal);
    }
  }

  // ── One attempt ─────────────────────────────────────────────────────────────────────

  private async attempt<T>(
    url: string,
    init: RequestInit,
    timeout: number,
    attempt: number,
    signal: AbortSignal | undefined,
  ): Promise<Attempt<T>> {
    const controller = new AbortController();
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, timeout);
    // AbortSignal.any would say this in one line and arrived in Node 20; this package supports
    // 18, so the two signals are joined by hand rather than by dropping a supported runtime.
    const forward = (): void => controller.abort();
    signal?.addEventListener('abort', forward, { once: true });

    try {
      if (signal?.aborted === true) {
        return { kind: 'failure', retryable: false, error: cancelled(attempt, signal.reason) };
      }
      const response = await this.config.fetch(url, { ...init, signal: controller.signal });
      return await readResponse<T>(response, attempt);
    } catch (cause) {
      if (timedOut) {
        return {
          kind: 'failure',
          // A timeout is retryable: it is the commonest transient failure there is, and the
          // idempotency key is exactly what makes replaying one safe.
          retryable: true,
          error: new ApiConnectionError(`The request timed out after ${timeout}ms.`, { attempts: attempt, cause }),
        };
      }
      if (signal?.aborted === true) {
        return { kind: 'failure', retryable: false, error: cancelled(attempt, signal.reason) };
      }
      return {
        kind: 'failure',
        retryable: true,
        error: new ApiConnectionError(
          `The request could not be completed: ${cause instanceof Error ? cause.message : String(cause)}`,
          { attempts: attempt, cause },
        ),
      };
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener('abort', forward);
    }
  }

  // ── Building the request ────────────────────────────────────────────────────────────

  private buildUrl(spec: RequestSpec): string {
    const path = spec.operation.path.replace(/\{(\w+)\}/g, (_match, name: string) => {
      const value = spec.path?.[name];
      if (value === undefined || value === '') {
        throw new PaymentFlowError(`\`${name}\` is required by ${spec.operation.id} and was not supplied.`);
      }
      return encodeURIComponent(value);
    });

    const query = new URLSearchParams();
    for (const [name, value] of Object.entries(spec.query ?? {})) {
      if (value === undefined || value === null) continue;
      // The descriptor is the contract's own list. Checking against it turns a mistyped
      // filter — which the API would silently ignore, returning a page that looks right and
      // is not — into an error on the line that made it.
      if (!spec.operation.queryParameters.includes(name)) {
        throw new PaymentFlowError(
          `\`${name}\` is not a query parameter of ${spec.operation.id}. ` +
            `It accepts: ${spec.operation.queryParameters.join(', ') || '(none)'}.`,
        );
      }
      // Encoded from the shape of the value, which is what the document's own styles come to:
      // an array repeats the name (`sort=a&sort=b`), and a map is `deepObject`
      // (`metadata[orderId]=A-1234`). `String(value)` on a map would send `[object Object]`,
      // the platform would ignore an unparseable filter, and the caller would get an
      // unfiltered page that looks like a correct answer to a narrower question. The
      // generator refuses to emit an object query parameter declared any other style, so this
      // rule cannot quietly stop matching the contract.
      if (Array.isArray(value)) {
        for (const element of value) query.append(name, String(element));
      } else if (isPlainObject(value)) {
        for (const [key, nested] of Object.entries(value)) {
          if (nested === undefined || nested === null) continue;
          query.append(`${name}[${key}]`, String(nested));
        }
      } else {
        query.append(name, String(value));
      }
    }

    const search = query.toString();
    return `${this.config.baseUrl}${path}${search === '' ? '' : `?${search}`}`;
  }

  private buildHeaders(spec: RequestSpec, options: RequestOptions): Record<string, string> {
    const headers: Record<string, string> = {
      Authorization: `Bearer ${this.config.apiKey}`,
      Accept: 'application/json',
      'PaymentFlow-Version': this.config.apiVersion,
      'User-Agent': this.config.userAgent,
    };
    if (spec.operation.hasRequestBody && spec.body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }
    if (options.correlationId !== undefined) {
      headers['X-Correlation-Id'] = options.correlationId;
    }
    // Read from the generated descriptor, never from a list kept here: a hand-maintained copy
    // of "which operations need a key" keeps answering the old question after the contract
    // moves, and the failure mode is a duplicated charge.
    if (spec.operation.requiredHeaders.includes(IDEMPOTENCY_HEADER)) {
      headers[IDEMPOTENCY_HEADER] = options.idempotencyKey ?? randomUUID();
    } else if (options.idempotencyKey !== undefined) {
      // The caller asked for one on an operation the contract does not require it for. Sent
      // rather than dropped: they know something about their own retry story that the
      // contract does not, and silently discarding it would be the worst of both.
      headers[IDEMPOTENCY_HEADER] = options.idempotencyKey;
    }
    return headers;
  }

  private isReplayable(operation: OperationDescriptor, headers: Record<string, string>): boolean {
    return operation.method === 'GET' || operation.method === 'DELETE' || IDEMPOTENCY_HEADER in headers;
  }
}

// ── Reading a response ──────────────────────────────────────────────────────────────────

type Attempt<T> =
  | { readonly kind: 'success'; readonly result: TransportResult<T> }
  | {
      readonly kind: 'failure';
      readonly retryable: boolean;
      readonly error: PaymentFlowError;
      readonly retryAfterSeconds?: number | undefined;
    };

async function readResponse<T>(response: Response, attempt: number): Promise<Attempt<T>> {
  const retryAfterSeconds = numberHeader(response, 'Retry-After');
  const meta: ResponseMeta = {
    statusCode: response.status,
    requestId: response.headers.get('X-Request-Id') ?? undefined,
    correlationId: response.headers.get('X-Correlation-Id') ?? undefined,
    apiVersion: response.headers.get('PaymentFlow-Version') ?? undefined,
    deprecated: response.headers.get('Deprecation') !== null,
    rateLimit: rateLimitMeta(response),
    attempts: attempt,
  };

  const raw = response.status === 204 ? '' : await response.text();
  let body: unknown;
  let unreadable = false;
  if (raw.length > 0) {
    try {
      body = JSON.parse(raw);
    } catch {
      unreadable = true;
    }
  }

  if (response.ok) {
    if (unreadable) {
      return {
        kind: 'failure',
        // The platform said 2xx and sent something this SDK cannot read. Retrying is the
        // right guess: the realistic cause is an intermediary that truncated the body.
        retryable: true,
        error: errorFromResponse(
          { message: 'The API returned a success status with a body that is not JSON.' },
          { statusCode: response.status, requestId: meta.requestId, correlationId: meta.correlationId, attempts: attempt },
        ),
      };
    }
    // Unknown fields ride along untouched — §9's forward-compatibility promise is kept by not
    // validating here, which is also why there is no schema check on this path.
    return { kind: 'success', result: { data: body as T, meta } };
  }

  return {
    kind: 'failure',
    retryable: isRetryableStatus(response.status),
    retryAfterSeconds,
    error: errorFromResponse(body, {
      statusCode: response.status,
      requestId: meta.requestId,
      correlationId: meta.correlationId,
      retryAfterSeconds,
      attempts: attempt,
    }),
  };
}

/**
 * 429 and 5xx, and nothing else.
 *
 * Every other 4xx describes a request that will be rejected identically however many times it
 * is sent; retrying one only delays the error the caller needs to see. 501 is excluded for the
 * same reason — an endpoint that is not implemented will not be implemented by the third try.
 */
function isRetryableStatus(status: number): boolean {
  return status === 429 || (status >= 500 && status !== 501);
}

function rateLimitMeta(response: Response): RateLimitMeta | undefined {
  const limit = numberHeader(response, 'RateLimit-Limit');
  const remaining = numberHeader(response, 'RateLimit-Remaining');
  const resetSeconds = numberHeader(response, 'RateLimit-Reset');
  if (limit === undefined && remaining === undefined && resetSeconds === undefined) {
    return undefined;
  }
  return { limit, remaining, resetSeconds };
}

function numberHeader(response: Response, name: string): number | undefined {
  const raw = response.headers.get(name);
  if (raw === null) return undefined;
  const value = Number(raw);
  return Number.isFinite(value) ? value : undefined;
}

// ── Backoff ─────────────────────────────────────────────────────────────────────────────

/**
 * How long to wait before the next attempt, or `undefined` to stop retrying.
 *
 * `Retry-After` wins over anything computed here, because it is the interval the platform will
 * actually accept the request again rather than a guess about when it might. The exception is
 * an interval so long that waiting it out would be indistinguishable from hanging — see D168.
 */
function retryDelay<T>(outcome: Extract<Attempt<T>, { kind: 'failure' }>, attempt: number): number | undefined {
  if (!outcome.retryable) return undefined;

  if (outcome.retryAfterSeconds !== undefined) {
    const requested = outcome.retryAfterSeconds * 1_000;
    return requested > MAX_HONOURED_RETRY_AFTER_MS ? undefined : requested;
  }

  // Full jitter: uniform over [0, ceiling) rather than ceiling/2 + jitter. With several
  // clients recovering from the same outage, the half-fixed form reconverges them into the
  // same synchronised wave that caused it; full jitter is what actually spreads the load.
  const ceiling = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * 2 ** (attempt - 1));
  return Math.random() * ceiling;
}

function sleep(ms: number, signal: AbortSignal | undefined): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    function onAbort(): void {
      clearTimeout(timer);
      reject(cancelled(0, signal?.reason));
    }
    if (signal?.aborted === true) {
      onAbort();
      return;
    }
    signal?.addEventListener('abort', onAbort, { once: true });
  });
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function cancelled(attempts: number, reason: unknown): PaymentFlowError {
  return new ApiConnectionError('The request was cancelled.', {
    attempts: attempts > 0 ? attempts : undefined,
    cause: reason,
  });
}
