/**
 * `client.events` — the event log behind webhooks (M22.3).
 *
 * The same events your endpoints receive, readable after the fact. This is what a receiver
 * reconciles against when it suspects it missed one, so both methods are read-only: an event
 * is a record of something that happened and is not a thing a caller creates.
 */

import { OPERATIONS } from '../generated/operations.js';
import type { EventResponse } from '../generated/models.js';
import type { RequestOptions } from '../transport.js';
import type { CursorPage } from '../pagination.js';
import { Resource } from './base.js';

/** What `events.list` accepts. */
export type EventListParams = {
  readonly limit?: number;
  readonly starting_after?: string;
  /** Only events of this type, such as `payment.captured`. */
  readonly type?: string;
  readonly created_after?: string;
  readonly created_before?: string;
};

export class Events extends Resource {
  /** Retrieves one event. */
  retrieve(id: string, options?: RequestOptions): Promise<EventResponse> {
    return this.send<EventResponse>(OPERATIONS.getEvent, { path: { id }, options });
  }

  /** Lists your events, most recent first. The result paginates transparently. */
  list(params: EventListParams = {}, options?: RequestOptions): Promise<CursorPage<EventResponse>> {
    return this.listCursor<EventResponse>(OPERATIONS.listEvents, params, options);
  }
}
