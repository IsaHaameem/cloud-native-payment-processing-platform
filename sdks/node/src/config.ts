/**
 * Client configuration, resolved once and then immutable (M22.2).
 *
 * §7.1 fixes the option names and defaults across all four languages, so this file is a
 * transcription of an agreed table rather than a design. What it adds is *validation*: every
 * option is checked at construction, because a client built with a negative timeout or an
 * empty base URL should fail on the line that built it, not on the first call — by which point
 * the stack trace points at a payment.
 */

import { API_VERSION, DEFAULT_BASE_URL } from './generated/contract.js';

/** The `fetch` shape this SDK depends on. Deliberately the platform's own, not a wrapper. */
export type FetchLike = (input: string, init: RequestInit) => Promise<Response>;

/**
 * Options accepted by the {@link PaymentFlow} constructor.
 *
 * Every property is `T | undefined` and not merely optional. Under
 * `exactOptionalPropertyTypes` those are different types, and the difference decides whether
 * the most natural line an integrator will ever write compiles:
 *
 * ```ts
 * new PaymentFlow({ apiKey: process.env.PAYMENTFLOW_API_KEY })
 * ```
 *
 * `process.env.X` is `string | undefined`, so with a bare `apiKey?: string` that is a type
 * error — and the fix a user would reach for is a non-null assertion on a credential, which is
 * exactly the wrong habit to teach. The strictness is worth keeping on *response* types, where
 * an absent field and an explicitly null one genuinely differ (D143); on *input* options it
 * only rejects callers for passing the value they have (D173).
 */
export interface PaymentFlowOptions {
  /**
   * Your secret API key, sent as `Authorization: Bearer <key>`.
   *
   * Falls back to `PAYMENTFLOW_API_KEY`. The key alone decides both whose data you see and
   * which mode you see it in — an `sk_test_` key reads and writes test data only. Nothing else
   * in this SDK can change that, deliberately.
   */
  readonly apiKey?: string | undefined;

  /** The host to call. Override for a local stack; defaults to the published host. */
  readonly baseUrl?: string | undefined;

  /**
   * The dated API revision to send as `PaymentFlow-Version`.
   *
   * Defaults to the revision this build was generated against, which is the only one its
   * types are known to describe. Overriding it is supported and is how you stay on a
   * superseded revision deliberately rather than by accident.
   */
  readonly apiVersion?: string | undefined;

  /** How long one HTTP attempt may take, in milliseconds. Default 30s. */
  readonly timeout?: number | undefined;

  /**
   * How many times a retryable failure is retried. Default 3, so a call makes at most four
   * attempts. Zero disables retrying without disabling anything else.
   */
  readonly maxRetries?: number | undefined;

  /**
   * The `fetch` implementation to use. Defaults to the global one.
   *
   * Injectable for tests and for proxy configuration — the two reasons §7.1 lists. This is
   * also what lets this package keep zero runtime dependencies while remaining testable
   * without a live server.
   */
  readonly fetch?: FetchLike | undefined;
}

/** The resolved, validated configuration a client holds. */
export interface ResolvedConfig {
  readonly apiKey: string;
  readonly baseUrl: string;
  readonly apiVersion: string;
  readonly timeout: number;
  readonly maxRetries: number;
  readonly fetch: FetchLike;
  readonly userAgent: string;
}

/** This package's own version. Kept here so the User-Agent and the public constant agree. */
export const SDK_VERSION = '0.1.0';

/**
 * How this SDK identifies itself, on every request.
 *
 * Not decoration: §7.1 notes that this is what makes SDK adoption measurable in the request
 * log M20 already records, which is the only way to answer "how many integrators are on a
 * version with a known bug" without asking them. The Node version is included because the
 * realistic SDK bug report is "it works on 20 and not on 18".
 */
export const USER_AGENT = `paymentflow-node/${SDK_VERSION} node/${process.versions.node}`;

/** Thrown when the client is constructed with options it cannot work with. */
export class PaymentFlowConfigurationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'PaymentFlowConfigurationError';
  }
}

export function resolveConfig(options: PaymentFlowOptions = {}): ResolvedConfig {
  const apiKey = options.apiKey ?? process.env['PAYMENTFLOW_API_KEY'];
  if (apiKey === undefined || apiKey.length === 0) {
    throw new PaymentFlowConfigurationError(
      'No API key. Pass `apiKey` to the PaymentFlow constructor, or set PAYMENTFLOW_API_KEY.',
    );
  }
  // A key with surrounding whitespace is the single most common way an `Authorization` header
  // comes out malformed — it survives a copy-paste out of a dashboard and a `.env` file, and
  // produces a 401 that looks like a revoked key. Rejected rather than trimmed, because
  // silently repairing a credential hides the fact that the stored one is wrong.
  if (apiKey.trim() !== apiKey) {
    throw new PaymentFlowConfigurationError('The API key has leading or trailing whitespace.');
  }

  const baseUrl = stripTrailingSlash(options.baseUrl ?? DEFAULT_BASE_URL);
  if (baseUrl.length === 0) {
    throw new PaymentFlowConfigurationError('`baseUrl` must not be empty.');
  }
  try {
    // eslint-disable-next-line no-new
    new URL(baseUrl);
  } catch {
    throw new PaymentFlowConfigurationError(`\`baseUrl\` is not a valid URL: ${baseUrl}`);
  }

  const apiVersion = options.apiVersion ?? API_VERSION;
  if (apiVersion.length === 0) {
    throw new PaymentFlowConfigurationError('`apiVersion` must not be empty.');
  }

  const timeout = options.timeout ?? 30_000;
  if (!Number.isFinite(timeout) || timeout <= 0) {
    throw new PaymentFlowConfigurationError('`timeout` must be a positive number of milliseconds.');
  }

  const maxRetries = options.maxRetries ?? 3;
  if (!Number.isInteger(maxRetries) || maxRetries < 0) {
    throw new PaymentFlowConfigurationError('`maxRetries` must be a non-negative integer.');
  }

  const fetchImpl = options.fetch ?? (globalThis.fetch as FetchLike | undefined);
  if (fetchImpl === undefined) {
    throw new PaymentFlowConfigurationError(
      'No `fetch` available. This SDK targets Node 18+, where fetch is global; on an older ' +
        'runtime, pass one through the `fetch` option.',
    );
  }

  return { apiKey, baseUrl, apiVersion, timeout, maxRetries, fetch: fetchImpl, userAgent: USER_AGENT };
}

function stripTrailingSlash(value: string): string {
  return value.endsWith('/') ? value.slice(0, -1) : value;
}
