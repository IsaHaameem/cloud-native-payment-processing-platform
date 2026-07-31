/**
 * `client.analytics`, `client.usage` and `client.requestLogs` — the three reporting surfaces
 * (M22.3).
 *
 * One file because each is a single operation and the three are read the same way; three
 * classes because they are three namespaces on the client and a caller should not have to know
 * they share a module.
 *
 * Note the two different window spellings, which are the platform's rather than this SDK's:
 * `analytics` takes RFC 3339 instants and `usage` takes calendar dates. Usage is metered per
 * UTC day, so a window with a time in it would imply a precision the meter does not have.
 */

import { OPERATIONS } from '../generated/operations.js';
import type { AnalyticsSummaryResponse, RequestLogResponse, UsageSummaryResponse } from '../generated/models.js';
import type { RequestOptions } from '../transport.js';
import type { CursorPage } from '../pagination.js';
import { Resource } from './base.js';

/** The reporting window for `analytics.retrievePaymentSummary`, as RFC 3339 instants. */
export type AnalyticsSummaryParams = {
  readonly from?: string;
  readonly to?: string;
};

export class Analytics extends Resource {
  /** Summarizes payment activity over a window, with hourly buckets. */
  retrievePaymentSummary(
    params: AnalyticsSummaryParams = {},
    options?: RequestOptions,
  ): Promise<AnalyticsSummaryResponse> {
    return this.send<AnalyticsSummaryResponse>(OPERATIONS.getPaymentAnalytics, { query: params, options });
  }
}

/** The reporting window for `usage.retrieve`, as calendar dates (`YYYY-MM-DD`). */
export type UsageSummaryParams = {
  readonly from?: string;
  readonly to?: string;
};

export class Usage extends Resource {
  /** Retrieves your API usage, metered per UTC day. */
  retrieve(params: UsageSummaryParams = {}, options?: RequestOptions): Promise<UsageSummaryResponse> {
    return this.send<UsageSummaryResponse>(OPERATIONS.getUsage, { query: params, options });
  }
}

/** What `requestLogs.list` accepts. */
export type RequestLogListParams = {
  readonly limit?: number;
  readonly starting_after?: string;
  readonly created_after?: string;
  readonly created_before?: string;
  /** Only calls that returned this status. */
  readonly status_code?: number;
  /** Only calls made with this HTTP method. */
  readonly method?: string;
};

export class RequestLogs extends Resource {
  /**
   * Lists your API calls, most recent first.
   *
   * Each row is keyed by the `requestId` this SDK reports on every response, so a call you
   * captured in your own logs can be looked up here directly.
   */
  list(params: RequestLogListParams = {}, options?: RequestOptions): Promise<CursorPage<RequestLogResponse>> {
    return this.listCursor<RequestLogResponse>(OPERATIONS.listRequestLogs, params, options);
  }
}
