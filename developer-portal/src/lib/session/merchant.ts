import 'server-only';

import { env } from '@/lib/env';

/**
 * Resolving the signed-in user's merchant, once, at login (M23.2).
 *
 * ── Why `/api/v1/merchants/me` and not the `/v1` tier ─────────────────────────────────
 *
 * The `/v1` tier is reached through M23.0's `SessionContextWebFilter`, which resolves the
 * merchant *itself* in order to sign the internal context — and answers 403 when there is none.
 * So `/v1` cannot tell the portal which merchant a user has; it can only refuse to serve a user
 * who has none.
 *
 * `GET /api/v1/merchants/me` is the dashboard-tier endpoint that answers the question directly.
 * It takes the user's own JWT through the gateway's OAuth2 resource server, so no new trust
 * relationship is introduced: the portal is asking, with the user's credential, for the user's
 * own merchant.
 *
 * ── Resolved at login, not per request ────────────────────────────────────────────────
 *
 * A user's merchant does not change during a session — a second one cannot be created for the
 * same owner, and `merchant-service` caches the lookup anyway. Reading it once and sealing it in
 * the cookie keeps it off the hot path entirely; onboarding, which is the one thing that changes
 * the answer, ends by writing a fresh session.
 *
 * ── 404 is a state, not a failure ─────────────────────────────────────────────────────
 *
 * `MerchantService.getMine` throws `ResourceNotFoundException` for a user who has not onboarded,
 * and the gateway returns 404. That is the ordinary state of a freshly verified account, so it
 * maps to `undefined` rather than to an error — the state `requireMerchant` exists to route to
 * onboarding.
 */

const TIMEOUT_MS = 10_000;

interface MerchantResponseBody {
  id?: unknown;
}

/**
 * @returns the merchant's id, or `undefined` when the user has not onboarded one.
 *
 * Never throws. A lookup that fails for any other reason — the service is down, the response is
 * a shape we do not recognise — also yields `undefined`, and that choice is deliberate: the
 * alternative is refusing to complete a login because a *secondary* fact could not be
 * established. The user lands on onboarding, which re-checks; an unnecessary trip there is a far
 * better failure than an unnecessary failure to sign in.
 */
export async function resolveMerchantId(accessToken: string): Promise<string | undefined> {
  try {
    const response = await fetch(`${env.gatewayUrl}/api/v1/merchants/me`, {
      headers: { Authorization: `Bearer ${accessToken}`, Accept: 'application/json' },
      cache: 'no-store',
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });

    if (!response.ok) return undefined;

    const body = (await response.json()) as MerchantResponseBody;
    return typeof body.id === 'string' && body.id.length > 0 ? body.id : undefined;
  } catch {
    return undefined;
  }
}
