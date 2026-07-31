/**
 * The PaymentFlow SDK for Node.js and TypeScript.
 *
 * ```ts
 * import { PaymentFlow } from 'paymentflow';
 *
 * const client = new PaymentFlow({ apiKey: process.env.PAYMENTFLOW_API_KEY });
 * const payment = await client.payments.create({ amountMinor: 1000, currency: 'USD' });
 * for await (const p of await client.payments.list({ status: 'captured' })) {
 *   console.log(p.id);
 * }
 * ```
 *
 * ## What this file is for
 *
 * This is the package's public API, decided here and nowhere else. `src/generated` is written
 * by `:sdks:shared` from `docs/openapi.yaml` and is regenerated in full whenever the contract
 * moves; `export * from './generated/...'` would make every name an integrator can import a
 * function of a code generator's decisions, so a refactor of the generator — or a Java class
 * renamed inside a service — would silently become a breaking change to this package.
 *
 * So the generated module's *runtime* values (the operation table, the enum value lists) are
 * never exported at all, and the generated *types* are re-exported one by one, by name, from
 * the list below. Adding a model to the contract does not add it to this SDK's API; someone
 * decides. `test/public-surface.test.mjs` asserts both halves of that rather than trusting it.
 */

// ── The client ──────────────────────────────────────────────────────────────────────────

export { PaymentFlow } from './client.js';

// ── Configuration ───────────────────────────────────────────────────────────────────────

export { PaymentFlowConfigurationError, USER_AGENT } from './config.js';
export type { FetchLike, PaymentFlowOptions, ResolvedConfig } from './config.js';

/**
 * This package's own version.
 *
 * Deliberately *not* the API revision. §7.3 pins SDK semver as independent of the dated
 * contract version: an SDK bug fix is a patch release against an unchanged API, and a new API
 * revision does not by itself change anything about this package. Conflating them would make
 * every contract revision a major version bump and every bug fix look like an API change.
 */
export { SDK_VERSION as VERSION } from './config.js';

/** The dated API revision this build was generated against, and the host it calls. */
export { API_VERSION, DEFAULT_BASE_URL } from './generated/contract.js';

// ── Errors ──────────────────────────────────────────────────────────────────────────────

export {
  ApiConnectionError,
  ApiError,
  AuthenticationError,
  IdempotencyError,
  InvalidRequestError,
  PaymentFlowError,
  PermissionError,
  RateLimitError,
} from './errors.js';
export type { PaymentFlowErrorDetail } from './errors.js';

// ── The request pipeline ────────────────────────────────────────────────────────────────

export type { RateLimitMeta, RequestOptions, ResponseMeta } from './transport.js';
export type { CursorPage, OffsetPage, Page } from './pagination.js';

// ── Resource namespaces and their parameters ────────────────────────────────────────────
//
// The classes are exported as types only. A caller may want to name one — a function that
// takes `client.payments`, say — but constructing one directly would mean constructing a
// transport directly, and that is not an API this package offers.

export type { Payments, PaymentCreateParams, PaymentListParams, PaymentRefundParams } from './resources/payments.js';
export type { Refunds, RefundListParams } from './resources/refunds.js';
export type { Balance, BalanceTransactions, BalanceTransactionListParams } from './resources/balance.js';
export type { Events, EventListParams } from './resources/events.js';
export type {
  Analytics,
  AnalyticsSummaryParams,
  RequestLogs,
  RequestLogListParams,
  Usage,
  UsageSummaryParams,
} from './resources/reporting.js';
export type {
  WebhookDeliveries,
  WebhookDeliveryListParams,
  WebhookEndpoints,
  WebhookEndpointCreateParams,
  WebhookEndpointUpdateParams,
} from './resources/webhooks.js';
export type { TestHelpers, DecisionListParams, SimulationOverrideCreateParams } from './resources/test-helpers.js';

// ── The contract's data shapes ──────────────────────────────────────────────────────────
//
// Types only, listed one by one. These are the objects the API returns, so an integrator needs
// to be able to name them; what they must not be able to do is depend on a name this package
// never meant to publish. The request models are deliberately absent — the parameter types
// above are the hand-written ones a caller actually passes.

export type {
  AnalyticsBucketResponse,
  AnalyticsSummaryResponse,
  ApiFieldError,
  BalanceResponse,
  BalanceTransactionResponse,
  CurrencyBalance,
  DecisionLogEntryResponse,
  EventResponse,
  PaymentResponse,
  RefundResponse,
  RequestLogResponse,
  SimulationOverrideResponse,
  TestCardResponse,
  UsageBucketResponse,
  UsageSummaryResponse,
  WebhookDeliveryAttemptResponse,
  WebhookDeliveryResponse,
  WebhookEndpointCreatedResponse,
  WebhookEndpointResponse,
} from './generated/models.js';

/**
 * The open vocabularies the contract documents.
 *
 * Open on purpose: §9 says new values ship without a new API revision, so each of these is a
 * union of the known values *plus* `string`. Code that switches on one must have a default
 * branch, and code that stores one must not validate against a fixed list.
 */
export type {
  ApiErrorType,
  BalanceTransactionResponseDirection,
  BalanceTransactionResponseMode,
  CreateSimulationOverrideRequestScenario,
  EventResponseMode,
  PaymentResponseMode,
  RefundResponseMode,
  RequestLogResponseMode,
  WebhookDeliveryAttemptResponseOutcome,
  WebhookDeliveryResponseStatus,
  WebhookEndpointResponseDisabledReason,
} from './generated/models.js';
