import 'server-only';

import type { ApiError, ApiErrorType } from '@/generated/models';

/**
 * The platform's error envelope, as exceptions (M23.1).
 *
 * One class per `ApiError.type`, and the mapping switches on **`type`, never `code`** — the
 * code set grows by policy and a UI branching on it would silently stop handling a case the
 * platform started sending. `docs/ERRORS.md` states this rule for integrators; the portal is an
 * integrator.
 *
 * The seven classes mirror the Node SDK's hierarchy deliberately. The portal does not depend on
 * that package (D188) but a developer moving between the two should not have to learn a second
 * vocabulary for the same six failures.
 */

/** Everything the platform told us about a failure. */
export interface PlatformErrorDetail {
  readonly status: number;
  readonly body: ApiError | undefined;
  readonly operationId: string;
}

export class PlatformError extends Error {
  readonly status: number;
  readonly type: ApiErrorType | undefined;
  readonly code: string | undefined;
  readonly requestId: string | undefined;
  readonly correlationId: string | undefined;
  readonly docUrl: string | undefined;
  readonly operationId: string;

  constructor(message: string, detail: PlatformErrorDetail) {
    super(message);
    this.name = new.target.name;
    this.status = detail.status;
    this.operationId = detail.operationId;
    this.type = detail.body?.type;
    this.code = detail.body?.code;
    this.requestId = detail.body?.requestId;
    this.correlationId = detail.body?.correlationId;
    this.docUrl = detail.body?.docUrl;
  }

  /**
   * What an error surface shows. `requestId` is included unconditionally because the error
   * contract tells a merchant to quote it in a support request — an error screen that omits it
   * makes the platform's own advice impossible to follow.
   */
  toDisplay(): { title: string; detail: string; requestId: string | undefined } {
    return {
      title: this.code ?? `HTTP ${this.status}`,
      detail: this.message,
      requestId: this.requestId,
    };
  }
}

/** 401 — the session is gone or was never valid. M23.2 turns this into a redirect to /login. */
export class AuthenticationError extends PlatformError {}

/** 403 — authenticated, but not allowed. Includes "this account has no merchant yet" (M23.0). */
export class PermissionDeniedError extends PlatformError {}

/** 400/404/409 — the request itself is wrong. */
export class InvalidRequestError extends PlatformError {}

/** 409 with `idempotency_error` — the one 4xx that may be retried. */
export class IdempotencyError extends PlatformError {}

/** 429 — over a rate limit or a daily quota. */
export class RateLimitError extends PlatformError {}

/** 5xx, or a response the platform could not produce at all. */
export class ApiStatusError extends PlatformError {}

/** The request never got an answer: DNS, connection reset, or a per-attempt timeout. */
export class ConnectionError extends PlatformError {}

type ErrorConstructor = new (message: string, detail: PlatformErrorDetail) => PlatformError;

/**
 * The six types the contract documents today.
 *
 * A plain lookup rather than an exhaustive `Record`, because `ApiErrorType` is generated as an
 * **open** union — `… | (string & {})` — precisely so a value the platform adds later does not
 * break a client that has not been rebuilt. An exhaustive map would not compile against an open
 * type, and forcing one would defeat the property the generator went out of its way to express.
 */
const BY_TYPE: Readonly<Record<string, ErrorConstructor>> = {
  authentication_error: AuthenticationError,
  permission_error: PermissionDeniedError,
  invalid_request_error: InvalidRequestError,
  idempotency_error: IdempotencyError,
  rate_limit_error: RateLimitError,
  api_error: ApiStatusError,
};

/**
 * Chooses the class for a failed response.
 *
 * Falls back to the HTTP status when `type` is absent or unrecognised — an unknown enum value
 * must be tolerated, not thrown on (§15's backward-compatibility rule applies to the portal
 * exactly as it does to an SDK), and a body that never arrived still has a status worth acting
 * on.
 */
export function toPlatformError(detail: PlatformErrorDetail): PlatformError {
  const message =
    detail.body?.message ?? `The platform returned ${detail.status} for ${detail.operationId}.`;

  const byType = detail.body?.type ? BY_TYPE[detail.body.type] : undefined;
  if (byType) return new byType(message, detail);

  if (detail.status === 401) return new AuthenticationError(message, detail);
  if (detail.status === 403) return new PermissionDeniedError(message, detail);
  if (detail.status === 429) return new RateLimitError(message, detail);
  if (detail.status >= 400 && detail.status < 500) return new InvalidRequestError(message, detail);
  return new ApiStatusError(message, detail);
}
