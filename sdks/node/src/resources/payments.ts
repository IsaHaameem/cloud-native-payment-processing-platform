/**
 * `client.payments` — the payment lifecycle (M22.3).
 *
 * Seven methods, one per published operation, and no eighth. There is no `createAndCapture`
 * convenience here even though it would be two obvious lines, because a method that performs
 * two chargeable calls behind one name is a method whose failure modes an integrator cannot
 * reason about: the second call failing leaves an authorized payment they did not know they
 * had. Every method here is exactly one HTTP request.
 */

import { OPERATIONS } from '../generated/operations.js';
import type { PaymentResponse } from '../generated/models.js';
import type { RequestOptions } from '../transport.js';
import type { CursorPage } from '../pagination.js';
import { Resource } from './base.js';

/** What `payments.create` accepts. */
export interface PaymentCreateParams {
  /**
   * The amount to charge, as an integer in the currency's minor unit: `1000` in `USD` is
   * $10.00. There are no floating-point amounts anywhere in this API.
   *
   * Required here even though the published schema does not list it under `required`. The
   * platform's own field is a primitive with a `@Positive` bound, so a body that omits it is
   * rejected with a 400 every time — the document understates the requirement, and an SDK
   * type that copied it would compile the one call the API always refuses (D170).
   */
  readonly amountMinor: number;
  /** The three-letter ISO 4217 currency code, such as `USD`. */
  readonly currency: string;
  /** An arbitrary description for your own records, up to 500 characters. */
  readonly description?: string;
  /**
   * A payment-method token to authorize against. In test mode, pass one from
   * `client.testHelpers.listCards()` to choose the outcome.
   */
  readonly paymentMethodToken?: string;
  /** Your own key-value pairs. Never interpreted by this platform. */
  readonly metadata?: Readonly<Record<string, string>>;
}

/** What `payments.list` accepts. Names are the contract's own, so they match the docs (D171). */
export type PaymentListParams = {
  readonly limit?: number;
  readonly starting_after?: string;
  readonly status?: string;
  readonly currency?: string;
  readonly amount_min?: number;
  readonly amount_max?: number;
  readonly created_after?: string;
  readonly created_before?: string;
  /** The only expandable relation on this resource is `refunds`. */
  readonly expand?: string;
  /** Containment filter, spelled `metadata[key]=value`. Every named key must match. */
  readonly metadata?: Readonly<Record<string, string>>;
};

/** What `payments.refund` accepts. All optional: an empty body refunds the full amount. */
export interface PaymentRefundParams {
  /** How much to refund, in minor units. Omit for the full remaining amount. */
  readonly amountMinor?: number;
  /** Why the refund was issued. */
  readonly reason?: string;
  readonly metadata?: Readonly<Record<string, string>>;
}

export class Payments extends Resource {
  /** Creates a payment. Generates an `Idempotency-Key` unless you supply one. */
  create(params: PaymentCreateParams, options?: RequestOptions): Promise<PaymentResponse> {
    return this.send<PaymentResponse>(OPERATIONS.createPayment, { body: params, options });
  }

  /** Retrieves one payment. */
  retrieve(id: string, params?: { readonly expand?: string }, options?: RequestOptions): Promise<PaymentResponse> {
    return this.send<PaymentResponse>(OPERATIONS.getPayment, { path: { id }, query: params, options });
  }

  /** Lists your payments, most recent first. The result paginates transparently. */
  list(params: PaymentListParams = {}, options?: RequestOptions): Promise<CursorPage<PaymentResponse>> {
    return this.listCursor<PaymentResponse>(OPERATIONS.listPayments, params, options);
  }

  /** Authorizes a created payment, reserving the funds. */
  authorize(id: string, options?: RequestOptions): Promise<PaymentResponse> {
    return this.send<PaymentResponse>(OPERATIONS.authorizePayment, { path: { id }, options });
  }

  /** Captures an authorized payment, moving the funds. */
  capture(id: string, options?: RequestOptions): Promise<PaymentResponse> {
    return this.send<PaymentResponse>(OPERATIONS.capturePayment, { path: { id }, options });
  }

  /**
   * Refunds a captured payment, in full or in part.
   *
   * Returns the **payment**, not the refund — the refund is in the payment's `refunds` array.
   * That is what the endpoint returns, and reshaping it here would mean either a second
   * request or a guess about which element is the new one.
   */
  refund(id: string, params: PaymentRefundParams = {}, options?: RequestOptions): Promise<PaymentResponse> {
    return this.send<PaymentResponse>(OPERATIONS.refundPayment, { path: { id }, body: params, options });
  }

  /** Voids an authorized payment, releasing the funds without capturing them. */
  void(id: string, options?: RequestOptions): Promise<PaymentResponse> {
    return this.send<PaymentResponse>(OPERATIONS.voidPayment, { path: { id }, options });
  }
}
