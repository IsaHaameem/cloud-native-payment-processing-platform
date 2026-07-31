/**
 * The typed error hierarchy (M22.2).
 *
 * ## Why there is a hierarchy at all
 *
 * A client that throws one error type for everything forces every integrator to write the same
 * `if (status === 401)` ladder, in their own words, against a contract they have to read to
 * discover. §7.1 pins the shape instead: seven classes, the same seven in every language, so
 * "retry this one, fix your key for that one, tell the customer about the third" is a `catch`
 * on a class rather than an inspection of a number.
 *
 * ## Why `type` and not the status code decides
 *
 * M22.0 published `ApiError.type` as a real enum (D163) precisely so an SDK would have a
 * stable field to branch on. Status codes are a poor substitute: a 409 is both
 * `IDEMPOTENCY_CONFLICT` — retryable, a concurrent request holding the same key — and
 * `PAYMENT_NOT_CAPTURABLE`, which will never succeed no matter how many times it is sent. The
 * platform already distinguishes those two, and mapping on status alone would throw that
 * distinction away at the point it matters most.
 *
 * Status is still the *fallback*, and has to be: a 502 written by a load balancer that never
 * reached this platform has no `type`, no body, and frequently no JSON at all.
 *
 * ## Forward compatibility
 *
 * §9 says new error types may ship without a new API revision, so an unrecognised `type` is
 * mapped by status rather than treated as a failure to parse. An SDK that threw
 * "unknown error type" would turn the platform's safest kind of change into an incident in
 * every integrator's code simultaneously.
 */

import type { ApiError as ApiErrorBody, ApiFieldError } from './generated/models.js';

/** Everything known about a failed call, assembled by the transport and shared by every class. */
export interface PaymentFlowErrorDetail {
  /** The HTTP status, when a response was received at all. */
  readonly statusCode?: number | undefined;
  /** The error's classification, as published by the platform. */
  readonly type?: string | undefined;
  /** The stable, machine-readable code, such as `PAYMENT_NOT_CAPTURABLE`. */
  readonly code?: string | undefined;
  /** The single offending parameter, when there is exactly one. */
  readonly param?: string | undefined;
  /** Field-level validation failures, when more than one field was rejected. */
  readonly fieldErrors?: readonly ApiFieldError[] | undefined;
  /** Identifies this one HTTP call. Quote it in a support request. */
  readonly requestId?: string | undefined;
  /** Identifies the whole distributed trace, which may span several services. */
  readonly correlationId?: string | undefined;
  /** Where to read about this specific code. */
  readonly docUrl?: string | undefined;
  /** How many attempts were made before this error was raised. Always at least 1. */
  readonly attempts?: number | undefined;
  /** The underlying failure, for transport errors. */
  readonly cause?: unknown;
}

/**
 * The base class every error this SDK throws extends.
 *
 * Catching this and nothing else is a complete, correct handler — which is the point. An
 * integrator who wants to distinguish cases narrows from here; one who does not is still safe.
 */
export class PaymentFlowError extends Error {
  readonly statusCode: number | undefined;
  readonly type: string | undefined;
  readonly code: string | undefined;
  readonly param: string | undefined;
  readonly fieldErrors: readonly ApiFieldError[] | undefined;
  readonly requestId: string | undefined;
  readonly correlationId: string | undefined;
  readonly docUrl: string | undefined;
  readonly attempts: number | undefined;

  constructor(message: string, detail: PaymentFlowErrorDetail = {}) {
    // `cause` goes through Error's own option so that `console.log` and Node's inspector
    // render the chain, rather than hiding the real failure inside a property nobody prints.
    super(message, detail.cause === undefined ? undefined : { cause: detail.cause });
    // Set explicitly rather than relying on the constructor: a class compiled to ES5 targets
    // loses its name here, and the name is what an integrator reads in a log.
    this.name = new.target.name;
    this.statusCode = detail.statusCode;
    this.type = detail.type;
    this.code = detail.code;
    this.param = detail.param;
    this.fieldErrors = detail.fieldErrors;
    this.requestId = detail.requestId;
    this.correlationId = detail.correlationId;
    this.docUrl = detail.docUrl;
    this.attempts = detail.attempts;
  }
}

/** The API key is missing, malformed, or not recognised. Retrying will not help. */
export class AuthenticationError extends PaymentFlowError {}

/** The key is valid but is not allowed to do this. Usually a missing scope, or the wrong mode. */
export class PermissionError extends PaymentFlowError {}

/**
 * The request was understood and rejected: a validation failure, an unknown id, or a state the
 * resource cannot move from. `param` and `fieldErrors` say which part.
 */
export class InvalidRequestError extends PaymentFlowError {}

/**
 * An `Idempotency-Key` problem — most often a concurrent request still holding the same key.
 *
 * Distinct from {@link InvalidRequestError} despite sharing a status, because this one *may*
 * succeed on a later attempt and the other never will. The platform separates
 * `IDEMPOTENCY_CONFLICT` from `CONFLICT` for exactly this reason.
 */
export class IdempotencyError extends PaymentFlowError {}

/**
 * The rate limit or the daily quota was exceeded.
 *
 * `retryAfterSeconds` is the platform's own answer to "when may I try again", taken from
 * `Retry-After` or `RateLimit-Reset`. The SDK's retry loop already waits it out; this field is
 * for a caller who has exhausted the retry budget and wants to schedule the work rather than
 * drop it.
 */
export class RateLimitError extends PaymentFlowError {
  /** Seconds to wait before retrying, when the response said. */
  readonly retryAfterSeconds: number | undefined;

  constructor(message: string, detail: PaymentFlowErrorDetail & { retryAfterSeconds?: number | undefined } = {}) {
    super(message, detail);
    this.retryAfterSeconds = detail.retryAfterSeconds;
  }
}

/**
 * The request never produced a response: DNS failure, connection reset, or the client-side
 * timeout elapsing.
 *
 * `cause` carries the underlying failure. There is no `statusCode`, because there was no reply
 * — which is also why this is the one error where "did it happen?" is genuinely unknown, and
 * why the idempotency key matters most here.
 */
export class ApiConnectionError extends PaymentFlowError {}

/**
 * The platform failed to handle a request it accepted — a 5xx, or a success this SDK could not
 * read. Not the caller's fault, and worth reporting with the `requestId`.
 *
 * Named `ApiError` to match §7.1's hierarchy across all four languages. The generated model of
 * the same name is the *body* of an error response and is internal to this package; nothing
 * exports both under one name.
 */
export class ApiError extends PaymentFlowError {}

/** The `type` values this SDK maps to a class, spelled as the platform publishes them. */
const BY_TYPE: Readonly<Record<string, new (message: string, detail: PaymentFlowErrorDetail) => PaymentFlowError>> = {
  authentication_error: AuthenticationError,
  permission_error: PermissionError,
  invalid_request_error: InvalidRequestError,
  idempotency_error: IdempotencyError,
  rate_limit_error: RateLimitError,
  api_error: ApiError,
};

/** What to raise when the body carried no usable `type`. */
function byStatus(statusCode: number | undefined): new (message: string, detail: PaymentFlowErrorDetail) => PaymentFlowError {
  if (statusCode === 401) return AuthenticationError;
  if (statusCode === 403) return PermissionError;
  if (statusCode === 429) return RateLimitError;
  if (statusCode !== undefined && statusCode >= 400 && statusCode < 500) return InvalidRequestError;
  return ApiError;
}

/** What the transport knows about a failed response, beyond the parsed body. */
export interface ErrorContext {
  readonly statusCode: number;
  readonly requestId?: string | undefined;
  readonly correlationId?: string | undefined;
  readonly retryAfterSeconds?: number | undefined;
  readonly attempts: number;
}

/**
 * Builds the error for a response the platform refused.
 *
 * `body` is whatever came back, which may be a well-formed {@link ApiErrorBody}, a JSON
 * document of some other shape, or nothing at all — every one of those is reachable in
 * production and none of them may throw from here. An error constructor that can itself fail
 * replaces a diagnosable failure with an undiagnosable one.
 */
export function errorFromResponse(body: unknown, context: ErrorContext): PaymentFlowError {
  const api: ApiErrorBody = isObject(body) ? (body as ApiErrorBody) : {};
  const type = typeof api.type === 'string' ? api.type : undefined;
  const constructor = (type !== undefined ? BY_TYPE[type] : undefined) ?? byStatus(context.statusCode);

  const message =
    typeof api.message === 'string' && api.message.length > 0
      ? api.message
      : `The API returned HTTP ${context.statusCode} with no error message.`;

  const detail: PaymentFlowErrorDetail & { retryAfterSeconds?: number | undefined } = {
    statusCode: context.statusCode,
    type,
    code: typeof api.code === 'string' ? api.code : undefined,
    param: typeof api.param === 'string' ? api.param : undefined,
    fieldErrors: Array.isArray(api.errors) ? api.errors : undefined,
    // The body's own requestId wins over the header, because it is the value the platform
    // wrote for this failure; the header is the fallback for a response with no usable body.
    requestId: typeof api.requestId === 'string' ? api.requestId : context.requestId,
    correlationId: typeof api.correlationId === 'string' ? api.correlationId : context.correlationId,
    docUrl: typeof api.docUrl === 'string' ? api.docUrl : undefined,
    attempts: context.attempts,
    retryAfterSeconds: context.retryAfterSeconds,
  };

  return new constructor(message, detail);
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
