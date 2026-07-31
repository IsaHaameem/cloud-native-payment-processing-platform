/**
 * `client.refunds` — reading refunds (M22.3).
 *
 * Read-only, because the API is. A refund is *created* through
 * {@link Payments.refund | `client.payments.refund()`}, which is where the platform puts it:
 * the operation is `POST /v1/payments/{id}/refund` and it returns the payment. Mirroring it
 * here as `refunds.create()` would be a second name for one endpoint, and the two would
 * disagree about what they return the moment either changed.
 */

import { OPERATIONS } from '../generated/operations.js';
import type { RefundResponse } from '../generated/models.js';
import type { RequestOptions } from '../transport.js';
import type { CursorPage } from '../pagination.js';
import { Resource } from './base.js';

/** What `refunds.list` accepts. */
export type RefundListParams = {
  readonly limit?: number;
  readonly starting_after?: string;
  /** Only refunds of this payment. */
  readonly payment?: string;
  readonly status?: string;
  readonly created_after?: string;
  readonly created_before?: string;
  /** Containment filter, spelled `metadata[key]=value`. */
  readonly metadata?: Readonly<Record<string, string>>;
};

export class Refunds extends Resource {
  /** Retrieves one refund. */
  retrieve(id: string, options?: RequestOptions): Promise<RefundResponse> {
    return this.send<RefundResponse>(OPERATIONS.getRefund, { path: { id }, options });
  }

  /** Lists your refunds, most recent first. The result paginates transparently. */
  list(params: RefundListParams = {}, options?: RequestOptions): Promise<CursorPage<RefundResponse>> {
    return this.listCursor<RefundResponse>(OPERATIONS.listRefunds, params, options);
  }
}
