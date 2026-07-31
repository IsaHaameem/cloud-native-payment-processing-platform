/*
 * Generated from docs/openapi.yaml by :sdks:shared. Do not edit.
 *
 * `./gradlew :sdks:shared:generateSdkSources` regenerates this file;
 * `./gradlew :sdks:shared:verifySdkSources` fails the build when it is stale,
 * which is why hand-editing it is not merely discouraged but pointless.
 */

/**
 * The error's classification, and **the field to branch on**. This is a small closed set an SDK maps to exception classes: `authentication_error`, `permission_error`, `invalid_request_error`, `idempotency_error`, `rate_limit_error`, `api_error`.
 *
 * New values may be added without a new API revision, so this type stays open: treat an unrecognised value as one you do not handle rather than as an error.
 */
export type ApiErrorType =
  | 'authentication_error'
  | 'permission_error'
  | 'invalid_request_error'
  | 'idempotency_error'
  | 'rate_limit_error'
  | 'api_error'
  | (string & {});

/**
 * The values `ApiErrorType` is documented to take today. New ones may be added without a new API revision, so this is a list to recognise against, never one to validate with.
 */
export const API_ERROR_TYPE_VALUES = [
  'authentication_error',
  'permission_error',
  'invalid_request_error',
  'idempotency_error',
  'rate_limit_error',
  'api_error',
] as const;

/**
 * Whether the entry added to (`CREDIT`) or removed from (`DEBIT`) the account.
 *
 * New values may be added without a new API revision, so this type stays open: treat an unrecognised value as one you do not handle rather than as an error.
 */
export type BalanceTransactionResponseDirection =
  | 'DEBIT'
  | 'CREDIT'
  | (string & {});

/**
 * The values `BalanceTransactionResponseDirection` is documented to take today. New ones may be added without a new API revision, so this is a list to recognise against, never one to validate with.
 */
export const BALANCE_TRANSACTION_RESPONSE_DIRECTION_VALUES = [
  'DEBIT',
  'CREDIT',
] as const;

/**
 * Whether this entry is `test` or `live` data.
 *
 * New values may be added without a new API revision, so this type stays open: treat an unrecognised value as one you do not handle rather than as an error.
 */
export type BalanceTransactionResponseMode =
  | 'test'
  | 'live'
  | (string & {});

/**
 * The values `BalanceTransactionResponseMode` is documented to take today. New ones may be added without a new API revision, so this is a list to recognise against, never one to validate with.
 */
export const BALANCE_TRANSACTION_RESPONSE_MODE_VALUES = [
  'test',
  'live',
] as const;

/**
 * Which behaviour to force. The scenario decides which of the fields below are required: `FORCE_DECLINE` needs `declineCode`, `FORCE_ERROR` needs `errorCode`, and the latency scenarios need `latencyMs`.
 *
 * New values may be added without a new API revision, so this type stays open: treat an unrecognised value as one you do not handle rather than as an error.
 */
export type CreateSimulationOverrideRequestScenario =
  | 'FORCE_DECLINE'
  | 'FORCE_ERROR'
  | 'INJECT_LATENCY'
  | 'FORCE_TIMEOUT'
  | 'FORCE_RATE_LIMIT'
  | 'DELAY_SETTLEMENT'
  | 'DUPLICATE_WEBHOOKS'
  | 'WEBHOOK_FAILURE'
  | (string & {});

/**
 * The values `CreateSimulationOverrideRequestScenario` is documented to take today. New ones may be added without a new API revision, so this is a list to recognise against, never one to validate with.
 */
export const CREATE_SIMULATION_OVERRIDE_REQUEST_SCENARIO_VALUES = [
  'FORCE_DECLINE',
  'FORCE_ERROR',
  'INJECT_LATENCY',
  'FORCE_TIMEOUT',
  'FORCE_RATE_LIMIT',
  'DELAY_SETTLEMENT',
  'DUPLICATE_WEBHOOKS',
  'WEBHOOK_FAILURE',
] as const;

/**
 * Whether this event describes `test` or `live` activity.
 *
 * New values may be added without a new API revision, so this type stays open: treat an unrecognised value as one you do not handle rather than as an error.
 */
export type EventResponseMode =
  | 'test'
  | 'live'
  | (string & {});

/**
 * The values `EventResponseMode` is documented to take today. New ones may be added without a new API revision, so this is a list to recognise against, never one to validate with.
 */
export const EVENT_RESPONSE_MODE_VALUES = [
  'test',
  'live',
] as const;

/**
 * Whether this payment is `test` or `live` data. Determined by the API key that created it and not changeable by any header, parameter or field.
 *
 * New values may be added without a new API revision, so this type stays open: treat an unrecognised value as one you do not handle rather than as an error.
 */
export type PaymentResponseMode =
  | 'test'
  | 'live'
  | (string & {});

/**
 * The values `PaymentResponseMode` is documented to take today. New ones may be added without a new API revision, so this is a list to recognise against, never one to validate with.
 */
export const PAYMENT_RESPONSE_MODE_VALUES = [
  'test',
  'live',
] as const;

/**
 * Whether this refund is `test` or `live` data. Inherited from the payment.
 *
 * New values may be added without a new API revision, so this type stays open: treat an unrecognised value as one you do not handle rather than as an error.
 */
export type RefundResponseMode =
  | 'test'
  | 'live'
  | (string & {});

/**
 * The values `RefundResponseMode` is documented to take today. New ones may be added without a new API revision, so this is a list to recognise against, never one to validate with.
 */
export const REFUND_RESPONSE_MODE_VALUES = [
  'test',
  'live',
] as const;

/**
 * Whether the request was made with a `test` or `live` key.
 *
 * New values may be added without a new API revision, so this type stays open: treat an unrecognised value as one you do not handle rather than as an error.
 */
export type RequestLogResponseMode =
  | 'test'
  | 'live'
  | (string & {});

/**
 * The values `RequestLogResponseMode` is documented to take today. New ones may be added without a new API revision, so this is a list to recognise against, never one to validate with.
 */
export const REQUEST_LOG_RESPONSE_MODE_VALUES = [
  'test',
  'live',
] as const;

/**
 * What happened: the receiver accepted it, rejected it, or was never reached.
 *
 * New values may be added without a new API revision, so this type stays open: treat an unrecognised value as one you do not handle rather than as an error.
 */
export type WebhookDeliveryAttemptResponseOutcome =
  | 'SUCCEEDED'
  | 'FAILED_STATUS'
  | 'FAILED_TRANSPORT'
  | 'BLOCKED'
  | (string & {});

/**
 * The values `WebhookDeliveryAttemptResponseOutcome` is documented to take today. New ones may be added without a new API revision, so this is a list to recognise against, never one to validate with.
 */
export const WEBHOOK_DELIVERY_ATTEMPT_RESPONSE_OUTCOME_VALUES = [
  'SUCCEEDED',
  'FAILED_STATUS',
  'FAILED_TRANSPORT',
  'BLOCKED',
] as const;

/**
 * Where the delivery stands: still pending, delivered, retrying, or dead-lettered after exhausting the retry schedule.
 *
 * New values may be added without a new API revision, so this type stays open: treat an unrecognised value as one you do not handle rather than as an error.
 */
export type WebhookDeliveryResponseStatus =
  | 'PENDING'
  | 'DELIVERED'
  | 'DEAD_LETTERED'
  | (string & {});

/**
 * The values `WebhookDeliveryResponseStatus` is documented to take today. New ones may be added without a new API revision, so this is a list to recognise against, never one to validate with.
 */
export const WEBHOOK_DELIVERY_RESPONSE_STATUS_VALUES = [
  'PENDING',
  'DELIVERED',
  'DEAD_LETTERED',
] as const;

/**
 * Why the endpoint was disabled. **This is how you tell "the platform turned this off because it kept failing" from "I turned this off"** — which decides whether re-enabling it requires fixing something first. Absent on an enabled endpoint.
 *
 * New values may be added without a new API revision, so this type stays open: treat an unrecognised value as one you do not handle rather than as an error.
 */
export type WebhookEndpointResponseDisabledReason =
  | 'CONSECUTIVE_FAILURES'
  | (string & {});

/**
 * The values `WebhookEndpointResponseDisabledReason` is documented to take today. New ones may be added without a new API revision, so this is a list to recognise against, never one to validate with.
 */
export const WEBHOOK_ENDPOINT_RESPONSE_DISABLED_REASON_VALUES = [
  'CONSECUTIVE_FAILURES',
] as const;

/**
 * AnalyticsBucketResponse
 */
export interface AnalyticsBucketResponse {
  /**
   * Payments authorized in this hour.
   */
  authorizedCount?: number;
  /**
   * The inclusive start of this bucket. Each bucket covers the hour beginning here.
   */
  bucketStart?: string;
  /**
   * Payments captured in this hour.
   */
  capturedCount?: number;
  /**
   * Payments created in this hour.
   */
  createdCount?: number;
  /**
   * The ISO 4217 currency these counts and amounts are for. Buckets are split by currency, so amounts are never mixed.
   */
  currency?: string;
  /**
   * Authorization attempts that failed in this hour.
   */
  failedCount?: number;
  /**
   * Always `analytics_bucket`.
   */
  object?: string;
  /**
   * Payments refunded in this hour.
   */
  refundedCount?: number;
  /**
   * Total captured in this hour, in the bucket's currency's minor unit.
   */
  totalCapturedAmountMinor?: number;
  /**
   * Total refunded in this hour, in the bucket's currency's minor unit.
   */
  totalRefundedAmountMinor?: number;
  /**
   * Authorizations released without capture in this hour.
   */
  voidedCount?: number;
}

/**
 * AnalyticsSummaryResponse
 */
export interface AnalyticsSummaryResponse {
  /**
   * How many payments were authorized.
   */
  authorizedCount?: number;
  /**
   * The hourly series behind these totals, split by currency. The series begins where this platform started recording it.
   */
  buckets?: AnalyticsBucketResponse[];
  /**
   * How many payments were captured.
   */
  capturedCount?: number;
  /**
   * How many payments were created in the window.
   */
  createdCount?: number;
  /**
   * How many authorization attempts failed.
   */
  failedCount?: number;
  /**
   * The start of the window these totals cover, inclusive.
   */
  from?: string;
  /**
   * Always `analytics_summary`.
   */
  object?: string;
  /**
   * How many payments were refunded, in whole or in part.
   */
  refundedCount?: number;
  /**
   * The share of authorization attempts that succeeded, between 0 and 1, computed as `authorizedCount / (authorizedCount + failedCount)`. Payments still in `created` are excluded — they have not been attempted, and counting them as failures would make the rate fall simply because traffic arrived.
   *
   * **Explicitly `null`**, never omitted, when nothing was attempted in the window: a rate over zero attempts is unknown rather than zero.
   */
  successRate?: number | null;
  /**
   * The end of the window these totals cover, inclusive.
   */
  to?: string;
  /**
   * Total captured across the window, in minor units. Not currency-split — use `buckets` for that.
   */
  totalCapturedAmountMinor?: number;
  /**
   * Total refunded across the window, in minor units.
   */
  totalRefundedAmountMinor?: number;
  /**
   * How many authorizations were released without capture.
   */
  voidedCount?: number;
}

/**
 * ApiError
 */
export interface ApiError {
  /**
   * A stable, machine-readable identifier for this specific failure, such as `PAYMENT_NOT_CAPTURABLE`. The set of codes grows by policy, which is why `type` and not this is what a client should switch on. Every code is catalogued in docs/ERRORS.md.
   */
  code?: string;
  /**
   * Identifies the whole distributed trace this call belongs to, which may span several services. Broader than `requestId`.
   */
  correlationId?: string;
  /**
   * Where to read about this specific code.
   */
  docUrl?: string;
  /**
   * Field-level validation failures, when the request failed validation in more than one place.
   */
  errors?: ApiFieldError[];
  /**
   * A human-readable explanation. Safe to log; not intended to be shown to your customers, and never programmatically parsed.
   */
  message?: string;
  /**
   * The single offending parameter, when there is exactly one. Multi-field validation failures use `errors` instead.
   */
  param?: string;
  /**
   * The request path that produced the error.
   */
  path?: string;
  /**
   * Identifies this one HTTP call. **Quote this in a support request** — it is on every log line and every row of your request log.
   */
  requestId?: string;
  /**
   * The HTTP status code, repeated in the body so a logged response is self-contained.
   */
  status?: number;
  /**
   * When the error occurred, as RFC 3339.
   */
  timestamp?: string;
  /**
   * The error's classification, and **the field to branch on**. This is a small closed set an SDK maps to exception classes: `authentication_error`, `permission_error`, `invalid_request_error`, `idempotency_error`, `rate_limit_error`, `api_error`.
   */
  type?: ApiErrorType;
}

/**
 * ApiFieldError
 */
export interface ApiFieldError {
  /**
   * The request field that failed validation.
   */
  field?: string;
  /**
   * Why it failed. The rejected value is deliberately never echoed back.
   */
  message?: string;
}

/**
 * BalanceResponse
 */
export interface BalanceResponse {
  /**
   * One entry per currency you hold a balance in. A currency you have never transacted in is absent rather than reported as zero.
   */
  balances?: CurrencyBalance[];
  /**
   * Always `balance`. The discriminator that identifies this object out of context.
   */
  object?: string;
}

/**
 * BalanceTransactionResponse
 */
export interface BalanceTransactionResponse {
  /**
   * Which of your accounts moved. `MERCHANT_PENDING` holds authorized funds; `MERCHANT_SETTLED` holds captured funds owed to you. The other leg of every entry touches the platform's clearing account and is deliberately not exposed.
   */
  accountType?: string;
  /**
   * The amount moved, in the currency's minor unit. Always positive — `direction` carries the sign.
   */
  amountMinor?: number;
  /**
   * When the entry was posted, as RFC 3339.
   */
  createdAt?: string;
  /**
   * The three-letter ISO 4217 currency code.
   */
  currency?: string;
  /**
   * Whether the entry added to (`CREDIT`) or removed from (`DEBIT`) the account.
   */
  direction?: BalanceTransactionResponseDirection;
  /**
   * Which payment lifecycle event produced the entry — an authorization, a capture, a refund, a void. This is the *why* of the movement.
   */
  eventType?: string;
  /**
   * Unique identifier for this ledger entry.
   */
  id?: string;
  /**
   * Whether this entry is `test` or `live` data.
   */
  mode?: BalanceTransactionResponseMode;
  /**
   * Always `balance_transaction`. The discriminator that identifies this object out of context.
   */
  object?: string;
  /**
   * The payment whose lifecycle produced this entry.
   */
  paymentId?: string;
}

/**
 * CreatePaymentRequest
 */
export interface CreatePaymentRequest {
  /**
   * The amount to charge, as an integer in the currency's **minor unit**: `1000` in `USD` is $10.00. There are no floating-point amounts anywhere in this API. Must be positive.
   */
  amountMinor?: number;
  /**
   * The three-letter ISO 4217 currency code, such as `USD`.
   */
  currency: string;
  /**
   * An arbitrary description of what is being paid for, up to 500 characters. For your own records — PaymentFlow never shows it to your customer.
   */
  description?: string;
  /**
   * Up to 20 arbitrary key-value pairs you can attach to this object. PaymentFlow never interprets them; they are returned on every read and are filterable with `metadata[key]=value`.
   */
  metadata?: Record<string, string>;
  /**
   * A payment-method token to authorize against. Optional: a payment created without one takes the mode's default authorization behaviour. In test mode, pass one of the tokens from `GET /v1/test/cards` to choose the outcome.
   */
  paymentMethodToken?: string;
}

/**
 * CreateSimulationOverrideRequest
 */
export interface CreateSimulationOverrideRequest {
  /**
   * The decline code to return. Required for `FORCE_DECLINE`, ignored otherwise.
   */
  declineCode?: string;
  /**
   * How long the override lasts, in seconds. The alternative to `remainingCount`; supply one of the two.
   */
  durationSeconds?: number;
  /**
   * The error code to fail with. Required for `FORCE_ERROR`, ignored otherwise.
   */
  errorCode?: string;
  /**
   * How long to stall, in milliseconds. Required for the latency scenarios, ignored otherwise.
   */
  latencyMs?: number;
  /**
   * How many authorizations the override applies to before it expires. **Supply this or `durationSeconds`** — an override with neither would never stop, and a sandbox you cannot get back out of is worse than one you cannot get into.
   */
  remainingCount?: number;
  /**
   * Which behaviour to force. The scenario decides which of the fields below are required: `FORCE_DECLINE` needs `declineCode`, `FORCE_ERROR` needs `errorCode`, and the latency scenarios need `latencyMs`.
   */
  scenario: CreateSimulationOverrideRequestScenario;
}

/**
 * CreateWebhookEndpointRequest
 */
export interface CreateWebhookEndpointRequest {
  /**
   * Your own label for this endpoint. Useful once you have more than one.
   */
  description?: string;
  /**
   * The event types to send here. Must name at least one — an endpoint subscribed to nothing looks identical to a broken platform from your side, so it is refused rather than accepted silently. Use `["*"]` to receive everything, including types added later.
   */
  enabledEvents: string[];
  /**
   * Up to 20 arbitrary key-value pairs you can attach to this object. PaymentFlow never interprets them; they are returned on every read and are filterable with `metadata[key]=value`.
   */
  metadata?: Record<string, string>;
  /**
   * Where to send deliveries. Must be reachable from the public internet over HTTPS: private, link-local and cloud-metadata addresses are refused, and the address is re-checked at delivery time so a hostname cannot be repointed at one afterwards.
   *
   * Not updatable later — the URL is half of an endpoint's identity.
   */
  url: string;
}

/**
 * CurrencyBalance
 */
export interface CurrencyBalance {
  /**
   * Money captured and owed to you, in the currency's minor unit, net of refunds.
   */
  availableMinor?: number;
  /**
   * The three-letter ISO 4217 currency code.
   */
  currency?: string;
  /**
   * Money authorized but not yet captured, in the currency's minor unit. Not yours yet — an authorization can still be voided or expire.
   */
  pendingMinor?: number;
}

/**
 * CursorPageBalanceTransactionResponse
 */
export interface CursorPageBalanceTransactionResponse {
  /**
   * The objects on this page, most recent first.
   */
  data?: BalanceTransactionResponse[];
  /**
   * Whether more objects exist after this page. A cursor page reports no total count — that would cost a second full count query on every request — and this is the only question a paginating client needs.
   */
  hasMore?: boolean;
  /**
   * Pass this as `starting_after` to fetch the next page. Absent when `hasMore` is false. Opaque and signed: treat it as a token, never parse or construct one.
   */
  nextCursor?: string;
  /**
   * Always `list`. A constant discriminator, so a client deserializing a response can branch on a field rather than on the endpoint it called.
   */
  object?: string;
}

/**
 * CursorPageEventResponse
 */
export interface CursorPageEventResponse {
  /**
   * The objects on this page, most recent first.
   */
  data?: EventResponse[];
  /**
   * Whether more objects exist after this page. A cursor page reports no total count — that would cost a second full count query on every request — and this is the only question a paginating client needs.
   */
  hasMore?: boolean;
  /**
   * Pass this as `starting_after` to fetch the next page. Absent when `hasMore` is false. Opaque and signed: treat it as a token, never parse or construct one.
   */
  nextCursor?: string;
  /**
   * Always `list`. A constant discriminator, so a client deserializing a response can branch on a field rather than on the endpoint it called.
   */
  object?: string;
}

/**
 * CursorPagePaymentResponse
 */
export interface CursorPagePaymentResponse {
  /**
   * The objects on this page, most recent first.
   */
  data?: PaymentResponse[];
  /**
   * Whether more objects exist after this page. A cursor page reports no total count — that would cost a second full count query on every request — and this is the only question a paginating client needs.
   */
  hasMore?: boolean;
  /**
   * Pass this as `starting_after` to fetch the next page. Absent when `hasMore` is false. Opaque and signed: treat it as a token, never parse or construct one.
   */
  nextCursor?: string;
  /**
   * Always `list`. A constant discriminator, so a client deserializing a response can branch on a field rather than on the endpoint it called.
   */
  object?: string;
}

/**
 * CursorPageRefundResponse
 */
export interface CursorPageRefundResponse {
  /**
   * The objects on this page, most recent first.
   */
  data?: RefundResponse[];
  /**
   * Whether more objects exist after this page. A cursor page reports no total count — that would cost a second full count query on every request — and this is the only question a paginating client needs.
   */
  hasMore?: boolean;
  /**
   * Pass this as `starting_after` to fetch the next page. Absent when `hasMore` is false. Opaque and signed: treat it as a token, never parse or construct one.
   */
  nextCursor?: string;
  /**
   * Always `list`. A constant discriminator, so a client deserializing a response can branch on a field rather than on the endpoint it called.
   */
  object?: string;
}

/**
 * CursorPageRequestLogResponse
 */
export interface CursorPageRequestLogResponse {
  /**
   * The objects on this page, most recent first.
   */
  data?: RequestLogResponse[];
  /**
   * Whether more objects exist after this page. A cursor page reports no total count — that would cost a second full count query on every request — and this is the only question a paginating client needs.
   */
  hasMore?: boolean;
  /**
   * Pass this as `starting_after` to fetch the next page. Absent when `hasMore` is false. Opaque and signed: treat it as a token, never parse or construct one.
   */
  nextCursor?: string;
  /**
   * Always `list`. A constant discriminator, so a client deserializing a response can branch on a field rather than on the endpoint it called.
   */
  object?: string;
}

/**
 * DecisionLogEntryResponse
 */
export interface DecisionLogEntryResponse {
  /**
   * When the decision was made, as RFC 3339.
   */
  createdAt?: string;
  /**
   * The idempotency key this decision was made under. Re-deciding with the same key returns the original verdict rather than deciding again, so a retry cannot change an outcome your integration already saw.
   */
  decisionKey?: string;
  /**
   * The decline code returned, when the outcome was a decline.
   */
  declineCode?: string;
  /**
   * How long the deferred outcome was scheduled to wait, in milliseconds.
   */
  deferredDelayMs?: number;
  /**
   * The operation whose outcome was deferred, when the decision scheduled one for later.
   */
  deferredOperation?: string;
  /**
   * The error code returned, when the outcome was an error.
   */
  errorCode?: string;
  /**
   * How long the simulated acquirer took to answer, in milliseconds.
   */
  latencyMs?: number;
  /**
   * Which operation was being decided — an authorization, a capture, a refund.
   */
  operation?: string;
  /**
   * What the simulated acquirer decided.
   */
  outcome?: string;
  /**
   * The simulation override that produced this decision, when `source` names one. A bare reference rather than the override's contents — enough to know one was involved without this endpoint re-exposing the simulation control surface.
   */
  overrideId?: string;
  /**
   * The payment this decision was made about.
   */
  paymentId?: string;
  /**
   * **Why the outcome was what it was**: the test card's own catalogue entry, an active simulation override, or the mode's default. This is the field that turns a surprising result into an explained one.
   */
  source?: string;
}

/**
 * EventResponse
 */
export interface EventResponse {
  /**
   * When the event occurred — not when it was recorded. Events are ordered by this, so a redelivery cannot reorder your feed.
   */
  created?: string;
  /**
   * The event payload — the object the event happened to, as it was at the time. Its shape depends on `type` and is the same body your webhook endpoint received. Stored verbatim: audit-service records what it was given rather than a typed view it would then have to keep in step.
   */
  data?: Record<string, unknown>;
  /**
   * Unique identifier for this event, `evt_` followed by 32 hex characters. **Byte-identical to the `id` in the webhook body you received for the same event**, so a webhook can be reconciled against this log without storing anything extra.
   */
  id?: string;
  /**
   * Whether this event describes `test` or `live` activity.
   */
  mode?: EventResponseMode;
  /**
   * Always `event`. The discriminator that identifies this object out of context.
   */
  object?: string;
  /**
   * What happened, from the frozen event vocabulary — `payment.created`, `payment.authorized`, `payment.captured`, `payment.refunded`, `payment.voided`, `payment.failed`. **New types may be added without a new API revision**, so treat an unrecognised type as one you do not handle.
   */
  type?: string;
}

/**
 * PageResponseDecisionLogEntryResponse
 */
export interface PageResponseDecisionLogEntryResponse {
  /**
   * The objects on this page.
   */
  content?: DecisionLogEntryResponse[];
  /**
   * Whether this is the first page.
   */
  first?: boolean;
  /**
   * Whether this is the last page.
   */
  last?: boolean;
  /**
   * The zero-based index of this page.
   */
  page?: number;
  /**
   * How many objects each page holds.
   */
  size?: number;
  /**
   * How many objects match the query in total.
   */
  totalElements?: number;
  /**
   * How many pages the result set spans.
   */
  totalPages?: number;
}

/**
 * PageResponseWebhookDeliveryResponse
 */
export interface PageResponseWebhookDeliveryResponse {
  /**
   * The objects on this page.
   */
  content?: WebhookDeliveryResponse[];
  /**
   * Whether this is the first page.
   */
  first?: boolean;
  /**
   * Whether this is the last page.
   */
  last?: boolean;
  /**
   * The zero-based index of this page.
   */
  page?: number;
  /**
   * How many objects each page holds.
   */
  size?: number;
  /**
   * How many objects match the query in total.
   */
  totalElements?: number;
  /**
   * How many pages the result set spans.
   */
  totalPages?: number;
}

/**
 * PaymentResponse
 */
export interface PaymentResponse {
  /**
   * The amount authorized, as an integer in the currency's minor unit: `1000` in `USD` is $10.00.
   */
  amountMinor?: number;
  /**
   * How much of the authorized amount has been captured so far, in minor units.
   */
  capturedAmountMinor?: number;
  /**
   * When the payment was created, as RFC 3339.
   */
  createdAt?: string;
  /**
   * The three-letter ISO 4217 currency code.
   */
  currency?: string;
  /**
   * The description supplied when the payment was created.
   */
  description?: string;
  /**
   * Why the payment failed, when `status` is `failed`. Absent otherwise — this is the acquirer's reason, not a validation message.
   */
  failureReason?: string;
  /**
   * Unique identifier for this payment.
   */
  id?: string;
  /**
   * The merchant this payment belongs to — always your own account.
   */
  merchantId?: string;
  /**
   * Up to 20 arbitrary key-value pairs you can attach to this object. PaymentFlow never interprets them; they are returned on every read and are filterable with `metadata[key]=value`.
   */
  metadata?: Record<string, string>;
  /**
   * Whether this payment is `test` or `live` data. Determined by the API key that created it and not changeable by any header, parameter or field.
   */
  mode?: PaymentResponseMode;
  /**
   * Always `payment`. The discriminator that identifies this object out of context.
   */
  object?: string;
  /**
   * The payment-method token this payment authorizes against, if one was supplied.
   */
  paymentMethodToken?: string;
  /**
   * How much has been refunded so far, in minor units. Never exceeds `capturedAmountMinor`.
   */
  refundedAmountMinor?: number;
  /**
   * The refunds issued against this payment. **Present only when you ask for it** with `?expand=refunds`, and omitted entirely otherwise rather than returned empty — an empty list would be indistinguishable from "this payment has no refunds".
   */
  refunds?: RefundResponse[];
  /**
   * Where this payment is in its lifecycle. Lowercase `snake_case` as of API revision `2026-08-01`; callers pinned to `2026-07-27` receive the older upper-case spelling. **New values may be added without a new revision**, so treat an unrecognised status as one you do not handle rather than as an error.
   */
  status?: string;
  /**
   * When the payment last changed, as RFC 3339.
   */
  updatedAt?: string;
}

/**
 * RefundRequest
 */
export interface RefundRequest {
  /**
   * How much to refund, in the currency's minor unit. Omit it — or send no body at all — to refund everything that remains captured.
   */
  amountMinor?: number;
  /**
   * Up to 20 arbitrary key-value pairs you can attach to this object. PaymentFlow never interprets them; they are returned on every read and are filterable with `metadata[key]=value`.
   */
  metadata?: Record<string, string>;
  /**
   * Why the refund was issued, up to 500 characters. For your own records; PaymentFlow never interprets it.
   */
  reason?: string;
}

/**
 * RefundResponse
 */
export interface RefundResponse {
  /**
   * The amount refunded, in the currency's minor unit.
   */
  amountMinor?: number;
  /**
   * When the refund was created, as RFC 3339.
   */
  createdAt?: string;
  /**
   * The three-letter ISO 4217 currency code, matching the payment's.
   */
  currency?: string;
  /**
   * Why the refund failed, when `status` is `failed`. Absent otherwise.
   */
  failureReason?: string;
  /**
   * Unique identifier for this refund.
   */
  id?: string;
  /**
   * The merchant this refund belongs to — always your own account.
   */
  merchantId?: string;
  /**
   * Up to 20 arbitrary key-value pairs you can attach to this object. PaymentFlow never interprets them; they are returned on every read and are filterable with `metadata[key]=value`.
   */
  metadata?: Record<string, string>;
  /**
   * Whether this refund is `test` or `live` data. Inherited from the payment.
   */
  mode?: RefundResponseMode;
  /**
   * Always `refund`. The discriminator that identifies this object out of context.
   */
  object?: string;
  /**
   * The payment this refund was issued against.
   */
  paymentId?: string;
  /**
   * The reason supplied when the refund was issued, if any.
   */
  reason?: string;
  /**
   * Where this refund is in its lifecycle. Lowercase `snake_case` as of API revision `2026-08-01`. New values may be added without a new revision.
   */
  status?: string;
  /**
   * When the refund last changed, as RFC 3339.
   */
  updatedAt?: string;
}

/**
 * RequestLogResponse
 */
export interface RequestLogResponse {
  /**
   * The client address the request arrived from.
   */
  clientIp?: string;
  /**
   * Identifies the whole distributed trace this call belongs to.
   */
  correlationId?: string;
  /**
   * How long the platform took to answer, in milliseconds. Server time only — it excludes the network.
   */
  durationMs?: number;
  /**
   * The catalogued error code, when the request failed. Absent for a successful request.
   */
  errorCode?: string;
  /**
   * Unique identifier for this log entry.
   */
  id?: string;
  /**
   * The API key the request was made with. Absent for a key that has since been deleted.
   */
  keyId?: string;
  /**
   * The HTTP method.
   */
  method?: string;
  /**
   * Whether the request was made with a `test` or `live` key.
   */
  mode?: RequestLogResponseMode;
  /**
   * Always `request_log`.
   */
  object?: string;
  /**
   * When the request arrived, as RFC 3339.
   */
  occurredAt?: string;
  /**
   * The path requested, as sent.
   */
  path?: string;
  /**
   * The query string, if any, with sensitive values already redacted.
   */
  queryString?: string;
  /**
   * The request body, **already redacted and truncated** at the edge before it was ever stored — card numbers, keys and secrets never reach this log.
   */
  requestBody?: string;
  /**
   * The request headers, with credential-bearing values redacted.
   */
  requestHeaders?: Record<string, string>;
  /**
   * Identifies this one HTTP call — the same value the response's error body carried, if it failed.
   */
  requestId?: string;
  /**
   * The response body, redacted and truncated on the same terms as the request body.
   */
  responseBody?: string;
  /**
   * The HTTP status returned.
   */
  statusCode?: number;
  /**
   * The `User-Agent` header, useful for telling one of your services from another.
   */
  userAgent?: string;
}

/**
 * SimulationOverrideResponse
 */
export interface SimulationOverrideResponse {
  /**
   * The decline code being returned, for a decline scenario. **Null** otherwise.
   */
  declineCode?: string | null;
  /**
   * Which part of the platform acts on this scenario. **`null` means the decision engine enforces it now**; a value names the release that will. The webhook scenarios are stored and validated but not yet acted on, and saying so here is more honest than accepting an override that silently does nothing.
   */
  enactedFrom?: string | null;
  /**
   * The error code being returned, for an error scenario. **Null** otherwise.
   */
  errorCode?: string | null;
  /**
   * When the override stops applying. **Null** for a count-bounded override.
   */
  expiresAt?: string | null;
  /**
   * Unique identifier for this override.
   */
  id?: string;
  /**
   * The latency being injected, in milliseconds. **Null** for a scenario that injects none.
   */
  latencyMs?: number | null;
  /**
   * How many authorizations the override still applies to. Counts down as it is used; **null** for a time-bounded override.
   */
  remainingCount?: number | null;
  /**
   * The behaviour being forced.
   */
  scenario?: string;
}

/**
 * TestCardResponse
 */
export interface TestCardResponse {
  /**
   * The card brand this token simulates.
   */
  brand?: string;
  /**
   * What a later capture against this token will do — succeed immediately, fail, or settle after a delay. Authorization and capture can behave differently on purpose: a card that authorizes cleanly and then fails to capture is a real and easily-missed case.
   */
  captureBehaviour?: string;
  /**
   * The decline code the authorization will carry, when `outcome` is a decline. **Null** otherwise.
   */
  declineCode?: string | null;
  /**
   * How long a deferred capture or refund waits before its outcome arrives, in milliseconds. **Null** on the tokens whose behaviour is immediate; where it is set, the result reaches you as a webhook, exactly as an asynchronous settlement would.
   */
  deferredDelayMs?: number | null;
  /**
   * What this token is for, in one line.
   */
  description?: string;
  /**
   * The error code the authorization will fail with, when `outcome` is an error. **Null** otherwise.
   */
  errorCode?: string | null;
  /**
   * How long the simulated acquirer will take to answer, in milliseconds. Non-zero on the tokens that exist to let you exercise timeouts deliberately rather than by waiting for a bad day.
   */
  latencyMs?: number;
  /**
   * What authorizing against this token does: approve, decline, or fail with an error. A decline is the acquirer saying no and is a normal outcome your integration must handle; an error is the acquirer being unreachable or broken.
   */
  outcome?: string;
  /**
   * What a later refund against this token will do.
   */
  refundBehaviour?: string;
  /**
   * The token to pass as `paymentMethodToken` when creating a payment. This is what selects the behaviour described by the rest of this object.
   */
  token?: string;
}

/**
 * UpdateWebhookEndpointRequest
 */
export interface UpdateWebhookEndpointRequest {
  /**
   * A new label for this endpoint. Omit to leave it unchanged.
   */
  description?: string;
  /**
   * Turn deliveries on or off. Re-enabling an endpoint the platform disabled resets its failure count — fix the receiver first, or it will simply be disabled again.
   */
  enabled?: boolean;
  /**
   * Replace the subscription list. Sent wholesale rather than merged, so this is the complete new list. Omit to leave it unchanged.
   */
  enabledEvents?: string[];
  /**
   * Replace the metadata. A supplied map replaces the stored one wholesale rather than merging, so `{}` clears it — a merge would leave no way to remove a key. Omit to leave it unchanged.
   */
  metadata?: Record<string, string>;
}

/**
 * UsageBucketResponse
 */
export interface UsageBucketResponse {
  /**
   * How many returned a 4xx.
   */
  clientErrors?: number;
  /**
   * The day this bucket covers, in UTC.
   */
  day?: string;
  /**
   * The API key the traffic was made with. Usage is grouped by key, so two keys hitting the same route on the same day produce two buckets. **Null** for traffic made with a key that has since been deleted — the usage is still a fact about your day.
   */
  keyId?: string | null;
  /**
   * The slowest single request in the bucket, in milliseconds.
   */
  maxDurationMs?: number;
  /**
   * Mean server-side duration in milliseconds. Null when the bucket had no traffic.
   */
  meanDurationMs?: number | null;
  /**
   * Median server-side duration in milliseconds. Null when the bucket had no traffic.
   */
  p50DurationMs?: number | null;
  /**
   * 95th-percentile server-side duration in milliseconds. **Explicitly null**, never omitted, when the bucket had no traffic: a percentile over zero requests is unknown rather than zero.
   */
  p95DurationMs?: number | null;
  /**
   * 99th-percentile server-side duration in milliseconds. Null when the bucket had no traffic.
   */
  p99DurationMs?: number | null;
  /**
   * How many requests fell in this bucket.
   */
  requests?: number;
  /**
   * The route pattern the requests hit, not the concrete path — so every payment retrieval aggregates together.
   */
  route?: string;
  /**
   * How many returned a 5xx.
   */
  serverErrors?: number;
}

/**
 * UsageSummaryResponse
 */
export interface UsageSummaryResponse {
  /**
   * The breakdown behind the totals, one bucket per day, key and route.
   */
  buckets?: UsageBucketResponse[];
  /**
   * The first day these totals cover, inclusive.
   */
  from?: string;
  /**
   * The last day these totals cover, inclusive.
   */
  to?: string;
  /**
   * How many of those returned a 4xx — your requests that were rejected.
   */
  totalClientErrors?: number;
  /**
   * How many API requests you made across the range.
   */
  totalRequests?: number;
  /**
   * How many returned a 5xx — this platform's failures, not yours.
   */
  totalServerErrors?: number;
}

/**
 * WebhookDeliveryAttemptResponse
 */
export interface WebhookDeliveryAttemptResponse {
  /**
   * Which attempt this was, starting at 1.
   */
  attemptNumber?: number;
  /**
   * When the attempt was made, as RFC 3339.
   */
  attemptedAt?: string;
  /**
   * How long the attempt took, in milliseconds.
   */
  durationMs?: number;
  /**
   * Why the attempt failed before it got a response — a DNS failure, a refused connection, a timeout, or an address the egress guard blocked. Absent when the receiver answered at all, even with an error status.
   */
  error?: string;
  /**
   * Unique identifier for this attempt.
   */
  id?: string;
  /**
   * What happened: the receiver accepted it, rejected it, or was never reached.
   */
  outcome?: WebhookDeliveryAttemptResponseOutcome;
  /**
   * The body sent — byte for byte what the signature was computed over.
   */
  requestBody?: string;
  /**
   * The headers sent, **including the `PaymentFlow-Signature`** this platform computed. That is deliberate and safe — a signature is not a secret, and comparing the value sent against the one you computed is how a verification failure gets diagnosed. The signing secret itself never appears here.
   */
  requestHeaders?: string;
  /**
   * The URL this attempt was sent to.
   */
  requestUrl?: string;
  /**
   * The body the receiver returned, truncated. Often the only explanation of a rejection.
   */
  responseBody?: string;
  /**
   * The headers the receiver returned.
   */
  responseHeaders?: string;
  /**
   * The HTTP status the receiver returned. Absent when it was never reached.
   */
  responseStatus?: number;
}

/**
 * WebhookDeliveryResponse
 */
export interface WebhookDeliveryResponse {
  /**
   * How many attempts have been made so far.
   */
  attemptCount?: number;
  /**
   * Every attempt, with the request sent and the response received. This is the point of the delivery log: the answer a merchant needs is almost never "failed", it is "failed with 502 and this body, four times".
   */
  attempts?: WebhookDeliveryAttemptResponse[];
  /**
   * When the delivery was created, as RFC 3339.
   */
  createdAt?: string;
  /**
   * The endpoint this delivery is addressed to.
   */
  endpointId?: string;
  /**
   * The event being delivered. The same `evt_` id you can read back at `/v1/events`, so a delivery can always be traced to what caused it.
   */
  eventId?: string;
  /**
   * The type of the event being delivered.
   */
  eventType?: string;
  /**
   * Unique identifier for this delivery.
   */
  id?: string;
  /**
   * When it was last attempted. Absent if it has not been attempted yet.
   */
  lastAttemptedAt?: string;
  /**
   * When the next retry is scheduled. Absent once the delivery has succeeded or been dead-lettered.
   */
  nextAttemptAt?: string;
  /**
   * Always `webhook_delivery`.
   */
  object?: string;
  /**
   * The delivery this one re-sends, when it was created by a replay. Absent on an original delivery.
   */
  replayedFromDeliveryId?: string;
  /**
   * Where the delivery stands: still pending, delivered, retrying, or dead-lettered after exhausting the retry schedule.
   */
  status?: WebhookDeliveryResponseStatus;
  /**
   * The URL it is being sent to, as it was when the delivery was created.
   */
  url?: string;
}

/**
 * WebhookEndpointCreatedResponse
 */
export interface WebhookEndpointCreatedResponse {
  /**
   * The endpoint, in the shape every other read returns it in.
   */
  endpoint?: WebhookEndpointResponse;
  /**
   * The signing secret, in full. **Shown exactly once — store it now.** Every delivery to this endpoint is signed with it, and verifying that signature is the only thing standing between your receiver and anyone who learns its URL. It cannot be retrieved afterwards: only a hash is kept, so the platform genuinely cannot show it again rather than merely declining to. Lost one is replaced by rotating, not by recovering.
   */
  signingSecret?: string;
}

/**
 * WebhookEndpointResponse
 */
export interface WebhookEndpointResponse {
  /**
   * The API revision delivery bodies are rendered in for this endpoint.
   */
  apiVersion?: string;
  /**
   * How many deliveries have failed in a row. Resets to zero on the first success. When it reaches the platform's threshold the endpoint is disabled automatically — a permanently broken receiver is not retried forever.
   */
  consecutiveFailureCount?: number;
  /**
   * When the endpoint was registered, as RFC 3339.
   */
  createdAt?: string;
  /**
   * Your own label for this endpoint.
   */
  description?: string;
  /**
   * When the endpoint was disabled, if it is.
   */
  disabledAt?: string;
  /**
   * Why the endpoint was disabled. **This is how you tell "the platform turned this off because it kept failing" from "I turned this off"** — which decides whether re-enabling it requires fixing something first. Absent on an enabled endpoint.
   */
  disabledReason?: WebhookEndpointResponseDisabledReason;
  /**
   * Whether deliveries are currently being sent. May be false because you disabled it or because the platform did — see `disabledReason`.
   */
  enabled?: boolean;
  /**
   * The event types this endpoint receives. `*` means every type, including ones added later.
   */
  enabledEvents?: string[];
  /**
   * Unique identifier for this endpoint.
   */
  id?: string;
  /**
   * Up to 20 arbitrary key-value pairs you can attach to this object. PaymentFlow never interprets them; they are returned on every read and are filterable with `metadata[key]=value`.
   */
  metadata?: Record<string, string>;
  /**
   * Whether this endpoint was carried over from the platform's pre-webhook-product configuration.
   */
  migratedFromLegacy?: boolean;
  /**
   * Always `webhook_endpoint`.
   */
  object?: string;
  /**
   * The first few characters of the signing secret, so you can tell which secret an endpoint holds. **The full secret is shown exactly once**, when the endpoint is created or its secret is rotated, and is not retrievable afterwards.
   */
  signingSecretPrefix?: string;
  /**
   * When the endpoint last changed, as RFC 3339.
   */
  updatedAt?: string;
  /**
   * Where deliveries are sent. Not updatable: the URL is half of an endpoint's identity, and repointing one would leave its delivery history attached to a destination that never received any of it. Register a new endpoint instead.
   */
  url?: string;
}
