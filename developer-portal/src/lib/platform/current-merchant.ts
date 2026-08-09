import 'server-only';

import { cache } from 'react';

import { type MerchantLookup, lookupMerchant } from './merchants';

/**
 * The signed-in user's merchant, read at most once per render (M23.2a).
 *
 * ── Why a request-scoped read and not another field in the session cookie ─────────────
 *
 * The shell displays the business name, and the dashboard displays the profile. Both need the
 * same fact, and they render in the same pass, so without deduplication one navigation makes two
 * identical calls to the same endpoint.
 *
 * The cheaper-looking answer is to seal `businessName` into the session alongside `merchantId`.
 * `session.ts` rules that out for a reason worth repeating: the cookie is sent on *every* request
 * including static assets, so display data there spends bandwidth on every request to save a
 * lookup on a few — and it goes stale the moment the merchant renames the business, which is a
 * thing settings will let them do. `merchantId` is in the cookie because it is an authorization
 * input consulted on every request; a name is not.
 *
 * React's `cache` gives the right scope for the alternative: memoised for one render pass,
 * discarded after it, never shared between users. `merchant-service` caches the underlying query
 * anyway (`@Cacheable("merchants")`), so the remaining cost is one local hop.
 */
export const currentMerchant: (accessToken: string) => Promise<MerchantLookup> = cache(
  async (accessToken: string) => lookupMerchant(accessToken),
);
