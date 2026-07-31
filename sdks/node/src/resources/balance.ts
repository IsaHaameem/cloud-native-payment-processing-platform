/**
 * `client.balance` and `client.balanceTransactions` — where the money is, and how it got
 * there (M22.3).
 *
 * Two namespaces rather than one because they are two resources with two shapes: a balance is
 * a single object you retrieve, and its transactions are a paginated list. Folding the list
 * into `balance.transactions()` would read well and make `client.balance` an object that is
 * sometimes a value and sometimes a namespace.
 */

import { OPERATIONS } from '../generated/operations.js';
import type { BalanceResponse, BalanceTransactionResponse } from '../generated/models.js';
import type { RequestOptions } from '../transport.js';
import type { CursorPage } from '../pagination.js';
import { Resource } from './base.js';

export class Balance extends Resource {
  /**
   * Retrieves your current balance.
   *
   * One entry per currency you hold a balance in; a currency you have never transacted in is
   * absent rather than reported as zero.
   */
  retrieve(options?: RequestOptions): Promise<BalanceResponse> {
    return this.send<BalanceResponse>(OPERATIONS.getBalance, { options });
  }
}

/** What `balanceTransactions.list` accepts. */
export type BalanceTransactionListParams = {
  readonly limit?: number;
  readonly starting_after?: string;
  readonly created_after?: string;
  readonly created_before?: string;
};

export class BalanceTransactions extends Resource {
  /** Lists the entries that moved your balance, most recent first. */
  list(
    params: BalanceTransactionListParams = {},
    options?: RequestOptions,
  ): Promise<CursorPage<BalanceTransactionResponse>> {
    return this.listCursor<BalanceTransactionResponse>(OPERATIONS.listBalanceTransactions, params, options);
  }
}
