/**
 * The `webhooks` namespace, in the shape §7.1 names it (M22.4).
 *
 * A plain object rather than a class, because it holds nothing: verification needs the raw
 * body, the header and the secret, and none of those belong to a client. That is also why the
 * same object is exported from the package root — a webhook receiver is frequently a separate
 * process from the one that calls the API, and it often has no API key at all. Requiring
 * `new PaymentFlow({ apiKey })` before a signature could be checked would mean either handing a
 * secret key to a process that does not need one, or not verifying.
 *
 * The functions themselves are exported individually from `./webhooks.js` too, so a caller who
 * imports only `constructEvent` gets only `constructEvent` — `"sideEffects": false` lets a
 * bundler drop the rest.
 */

import {
  constructEvent,
  signatureHeaderFor,
  signPayload,
  DEFAULT_TOLERANCE_SECONDS,
  SIGNATURE_HEADER,
} from './webhooks.js';

/** What `client.webhooks` and the exported `webhooks` object offer. */
export interface Webhooks {
  readonly constructEvent: typeof constructEvent;
  readonly signPayload: typeof signPayload;
  readonly signatureHeaderFor: typeof signatureHeaderFor;
  readonly SIGNATURE_HEADER: typeof SIGNATURE_HEADER;
  readonly DEFAULT_TOLERANCE_SECONDS: typeof DEFAULT_TOLERANCE_SECONDS;
}

export const webhooks: Webhooks = {
  constructEvent,
  signPayload,
  signatureHeaderFor,
  SIGNATURE_HEADER,
  DEFAULT_TOLERANCE_SECONDS,
};
